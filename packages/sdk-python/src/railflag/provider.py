"""OpenFeature provider. Evaluates in process: no network call per flag check."""

from __future__ import annotations

import logging
from datetime import date, datetime, timezone
from typing import Any, Mapping, Optional, Sequence, Union

from openfeature.evaluation_context import EvaluationContext
from openfeature.event import ProviderEventDetails
from openfeature.exception import ErrorCode
from openfeature.flag_evaluation import FlagResolutionDetails, Reason
from openfeature.provider import AbstractProvider
from openfeature.provider.metadata import Metadata
from openfeature.track import TrackingEventDetails

from .evaluate import evaluate
from .events import EventSender
from .experiments import exposure_for, track_for
from .exposures import ExposureCounter
from .source import ConfigSource

_ERRORS = {
    "FLAG_NOT_FOUND": ErrorCode.FLAG_NOT_FOUND,
    "TYPE_MISMATCH": ErrorCode.TYPE_MISMATCH,
    "PARSE_ERROR": ErrorCode.PARSE_ERROR,
    "GENERAL": ErrorCode.GENERAL,
}

JsonValue = Union[Sequence[Any], Mapping[str, Any]]


def _plain(v: Any) -> Any:
    """Context values as the evaluator expects them: datetimes become epoch milliseconds."""
    if isinstance(v, datetime):
        if v.tzinfo is None:
            v = v.replace(tzinfo=timezone.utc)
        return v.timestamp() * 1000
    if isinstance(v, date):
        return datetime(v.year, v.month, v.day, tzinfo=timezone.utc).timestamp() * 1000
    if isinstance(v, (list, tuple)):
        return [_plain(x) for x in v]
    if isinstance(v, Mapping):
        return {k: _plain(x) for k, x in v.items()}
    return v


def to_context(ctx: Optional[EvaluationContext]) -> dict[str, Any]:
    if ctx is None:
        return {}
    out = {k: _plain(v) for k, v in (ctx.attributes or {}).items()}
    if ctx.targeting_key is not None:
        out["targetingKey"] = ctx.targeting_key
    return out


class RailflagProvider(AbstractProvider):
    """
    ```python
    from openfeature import api
    from railflag import RailflagProvider

    api.set_provider(RailflagProvider(sdk_key=os.environ["RAILFLAG_SERVER_KEY"], base_url="https://relay.railflag.com"))
    client = api.get_client()
    client.get_boolean_value("new-checkout", False, EvaluationContext("user-42", {"plan": "pro"}))
    ```
    """

    def __init__(
        self,
        sdk_key: str,
        base_url: str,
        stream: bool = True,
        poll_interval: float = 30.0,
        timeout: float = 10.0,
        logger: Optional[logging.Logger] = None,
        send_exposures: bool = True,
        exposure_interval: float = 60.0,
        send_events: bool = True,
        events_interval: float = 10.0,
    ) -> None:
        """
        ``send_exposures`` sends evaluation counts per flag and variation once a minute, so Release Watch can
        measure rollouts. Only counts are sent: no contexts or user ids. Turn it off with ``send_exposures=False``.

        ``send_events`` sends experiment data every ``events_interval`` seconds: which variation each user got,
        for flags in a running experiment only, and the ``client.track()`` events an experiment counts. This sends
        user keys (Railflag hashes them before storing them), and nothing while no experiment runs. Turn it off
        with ``send_events=False``.
        """
        super().__init__()
        self.source = ConfigSource(sdk_key, base_url=base_url, stream=stream, poll_interval=poll_interval, timeout=timeout, logger=logger)
        self.source.on_change(self._changed)
        self.exposures: Optional[ExposureCounter] = (
            ExposureCounter(sdk_key, self.source.base_url, interval=exposure_interval, logger=logger) if send_exposures else None
        )
        self.tracking: Optional[EventSender] = (
            EventSender(sdk_key, self.source.base_url, interval=events_interval, logger=logger) if send_events else None
        )

    def get_metadata(self) -> Metadata:
        return Metadata(name="railflag")

    def initialize(self, evaluation_context: EvaluationContext) -> None:
        self.source.start()
        if self.exposures:
            self.exposures.start()
        if self.tracking:
            self.tracking.start()

    def shutdown(self) -> None:
        self.source.stop()
        if self.exposures:
            self.exposures.stop()
        if self.tracking:
            self.tracking.stop()

    def track(
        self,
        tracking_event_name: str,
        evaluation_context: Optional[EvaluationContext] = None,
        tracking_event_details: Optional[TrackingEventDetails] = None,
    ) -> None:
        """OpenFeature tracking: ``client.track("purchase", ctx, TrackingEventDetails(value=49.9))``.
        Sent only while a running experiment counts the event."""
        snap = self.source.snapshot
        if self.tracking is None or snap is None:
            return
        value = tracking_event_details.value if tracking_event_details is not None else None
        t = track_for(snap.get("experiments"), tracking_event_name, to_context(evaluation_context), value)
        if t:
            self.tracking.event(t)

    def _changed(self, snap: dict[str, Any], prev: Optional[dict[str, Any]]) -> None:
        if prev is None:
            return  # the first load is the READY event, which OpenFeature sends itself
        before, after = prev.get("flags", {}), snap.get("flags", {})
        changed = sorted(k for k in set(before) | set(after) if before.get(k) != after.get(k))
        self.emit_provider_configuration_changed(ProviderEventDetails(flags_changed=changed, message=f"config version {snap['version']}"))

    def _resolve(self, flag_key: str, default: Any, ctx: Optional[EvaluationContext], expected: str) -> FlagResolutionDetails[Any]:
        snap = self.source.snapshot
        if snap is None:
            return FlagResolutionDetails(value=default, reason=Reason.ERROR, error_code=ErrorCode.PROVIDER_NOT_READY)
        context = to_context(ctx)
        r = evaluate(snap, flag_key, context, expected_type=expected)
        if r.reason == "ERROR":
            return FlagResolutionDetails(value=default, reason=Reason.ERROR, error_code=_ERRORS.get(r.error_code or "", ErrorCode.GENERAL), error_message=r.error_message)
        meta: dict[str, Union[str, int]] = {"flagVersion": r.flag_version or 0, "configVersion": snap["version"]}
        if r.rule_id:
            meta["ruleId"] = r.rule_id
        if self.exposures is not None and r.variation_index is not None:
            self.exposures.record(flag_key, r.variation_index)
        if self.tracking is not None:
            x = exposure_for(snap.get("experiments"), flag_key, context, r.reason, r.variation_index)
            if x:
                self.tracking.exposure(x)
        return FlagResolutionDetails(value=r.value, reason=r.reason, variant=str(r.variation_index), flag_metadata=meta)

    @staticmethod
    def _mismatch(default: Any, message: str) -> FlagResolutionDetails[Any]:
        return FlagResolutionDetails(value=default, reason=Reason.ERROR, error_code=ErrorCode.TYPE_MISMATCH, error_message=message)

    def resolve_boolean_details(self, flag_key: str, default_value: bool, evaluation_context: Optional[EvaluationContext] = None) -> FlagResolutionDetails[bool]:
        return self._resolve(flag_key, default_value, evaluation_context, "boolean")

    def resolve_string_details(self, flag_key: str, default_value: str, evaluation_context: Optional[EvaluationContext] = None) -> FlagResolutionDetails[str]:
        return self._resolve(flag_key, default_value, evaluation_context, "string")

    def resolve_integer_details(self, flag_key: str, default_value: int, evaluation_context: Optional[EvaluationContext] = None) -> FlagResolutionDetails[int]:
        d = self._resolve(flag_key, default_value, evaluation_context, "number")
        if d.error_code:
            return d
        if isinstance(d.value, float):
            if not d.value.is_integer():
                return self._mismatch(default_value, f"Flag {flag_key} is {d.value}, not an integer")
            d.value = int(d.value)
        return d

    def resolve_float_details(self, flag_key: str, default_value: float, evaluation_context: Optional[EvaluationContext] = None) -> FlagResolutionDetails[float]:
        d = self._resolve(flag_key, default_value, evaluation_context, "number")
        if not d.error_code:
            d.value = float(d.value)
        return d

    def resolve_object_details(self, flag_key: str, default_value: JsonValue, evaluation_context: Optional[EvaluationContext] = None) -> FlagResolutionDetails[JsonValue]:
        d = self._resolve(flag_key, default_value, evaluation_context, "json")
        if not d.error_code and not isinstance(d.value, (dict, list)):
            return self._mismatch(default_value, f"Flag {flag_key} value is not an object or list")
        return d
