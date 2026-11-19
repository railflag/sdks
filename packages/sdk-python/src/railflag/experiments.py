"""What the provider sends for experiments, ported from `packages/eval/src/experiments.ts`. Pure, like the
evaluator, and pinned for every SDK by `packages/eval/vectors/events-vectors.json`; change them together."""

from __future__ import annotations

import math
from collections import OrderedDict
from typing import Any, Mapping, Optional, TypedDict

from ._js import is_number
from .evaluate import Context, context_key_of


class ExposureRecord(TypedDict):
    flag: str
    variation: int
    key: str


class TrackRecord(TypedDict):
    event: str
    key: str
    value: float


def _user_key(ctx: Context) -> Optional[str]:
    key = context_key_of(ctx)
    return key or None  # an empty key is no user


def exposure_for(experiments: Optional[Mapping[str, Any]], flag_key: str, ctx: Context, reason: str, variation_index: Optional[int]) -> Optional[ExposureRecord]:
    """The exposure an evaluation produces, or None. Only randomized users count: reason SPLIT, with a user key."""
    if not experiments or flag_key not in experiments.get("flags", ()):
        return None
    if reason != "SPLIT" or variation_index is None:
        return None
    key = _user_key(ctx)
    if key is None:
        return None
    return {"flag": flag_key, "variation": variation_index, "key": key}


def _finite(v: Any) -> float:
    """`typeof v === 'number' && Number.isFinite(v) ? v : 0`. Booleans are not numbers."""
    if not is_number(v):
        return 0
    try:
        return v if math.isfinite(v) else 0
    except OverflowError:  # an int too large for a float
        return 0


def track_for(experiments: Optional[Mapping[str, Any]], event: str, ctx: Context, value: Any = None) -> Optional[TrackRecord]:
    """The event a `track()` call produces, or None. A missing or non-finite value counts as 0."""
    if not experiments or event not in experiments.get("events", ()):
        return None
    key = _user_key(ctx)
    if key is None:
        return None
    return {"event": event, "key": key, "value": _finite(value)}


class ExposureDedupe:
    """Remembers which (flag, variation, key) were already sent, so each is sent once. Oldest entries go first
    when full. Not thread safe on its own: `EventSender` calls it under its lock."""

    def __init__(self, max_size: int = 50_000) -> None:
        self.max_size = max_size
        self._seen: OrderedDict[tuple[str, int, str], None] = OrderedDict()

    def first(self, r: ExposureRecord) -> bool:
        """True the first time a record is seen."""
        rid = (r["flag"], r["variation"], r["key"])
        if rid in self._seen:
            return False
        if len(self._seen) >= self.max_size:
            self._seen.popitem(last=False)
        self._seen[rid] = None
        return True
