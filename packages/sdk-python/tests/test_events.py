"""Experiment exposures and track() events. Which records are sent is pinned for every SDK by
`packages/eval/vectors/events-vectors.json`, generated from `packages/eval` by `packages/eval/vectors/build.ts`."""

from __future__ import annotations

import copy
import json
import logging
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Optional

import pytest
from openfeature import api
from openfeature.evaluation_context import EvaluationContext
from openfeature.track import TrackingEventDetails

from railflag import RailflagProvider, __version__
from railflag.evaluate import evaluate
from railflag.events import BATCH, MAX_QUEUE, EventSender
from railflag.experiments import ExposureDedupe, exposure_for, track_for
from test_provider import KEY, FakeRelay, wait_for

VECTORS = json.loads((Path(__file__).parents[2] / "eval" / "vectors" / "events-vectors.json").read_text("utf-8"))
SNAP = VECTORS["snapshot"]


def of_context(c: dict[str, Any]) -> EvaluationContext:
    """A vector context as OpenFeature carries it: `targetingKey` is the targeting key, the rest are attributes."""
    return EvaluationContext(c.get("targetingKey"), {k: v for k, v in c.items() if k != "targetingKey"})


def sent(relay: FakeRelay) -> dict[str, list[dict[str, Any]]]:
    """What the relay received, in order and without `ts`."""
    return {
        "exposures": [{k: x[k] for k in ("flag", "variation", "key")} for b in relay.events for x in b["exposures"]],
        "events": [{k: e[k] for k in ("event", "key", "value")} for b in relay.events for e in b["events"]],
    }


@pytest.fixture
def relay():
    r = FakeRelay()
    r.snap = copy.deepcopy(SNAP)
    yield r
    api.shutdown()
    api.clear_providers()
    r.close()


def start(relay: FakeRelay, **opts: Any) -> RailflagProvider:
    provider = RailflagProvider(sdk_key=KEY, base_url=relay.url, stream=False, send_exposures=False, events_interval=60, **opts)
    api.set_provider_and_wait(provider)
    return provider


# ---- vectors -----------------------------------------------------------------------------

def test_exposure_vectors():
    for case in VECTORS["exposures"]:
        r = evaluate(SNAP, case["flag"], case["context"])
        assert {"reason": r.reason, "variationIndex": r.variation_index} == case["result"], case
        assert exposure_for(SNAP["experiments"], case["flag"], case["context"], r.reason, r.variation_index) == case["expect"], case
    assert len(VECTORS["exposures"]) > 300


def test_track_vectors():
    for case in VECTORS["tracks"]:
        value = (case["details"] or {}).get("value")
        assert track_for(SNAP["experiments"], case["event"], case["context"], value) == case["expect"], case
    assert len(VECTORS["tracks"]) >= 30


@pytest.mark.parametrize("seq", VECTORS["sequences"], ids=lambda s: s["name"])
def test_sequences_through_the_client(relay: FakeRelay, seq: dict[str, Any]) -> None:
    provider = start(relay)
    c = api.get_client()
    for step in seq["steps"]:
        ctx = of_context(step["context"])
        if "evaluate" in step:
            key = step["evaluate"]
            if SNAP["flags"].get(key, {}).get("type") == "string":
                c.get_string_value(key, "", ctx)
            else:
                c.get_boolean_value(key, False, ctx)
        else:
            details: Optional[dict[str, Any]] = step.get("details")
            c.track(step["track"], ctx, None if details is None else TrackingEventDetails(value=details.get("value")))
    assert provider.tracking is not None
    provider.tracking.flush()
    assert sent(relay) == seq["expect"]


@pytest.mark.parametrize("value,expected", [
    (49.9, 49.9), (0, 0), (-3, -3), (None, 0), (float("nan"), 0), (float("inf"), 0), (True, 0), ("12", 0), (10**400, 0),
])
def test_track_values(value: Any, expected: float) -> None:
    r = track_for(SNAP["experiments"], "purchase", {"targetingKey": "u"}, value)
    assert r == {"event": "purchase", "key": "u", "value": expected}


def test_dedupe_evicts_the_oldest_and_keeps_variations_apart() -> None:
    d = ExposureDedupe(max_size=2)
    a, b, c = ({"flag": "f", "variation": 0, "key": k} for k in "abc")
    assert d.first(a) and d.first(b) and not d.first(a)
    assert d.first({"flag": "f", "variation": 1, "key": "a"}) is True  # a new variation is a new record
    assert d.first(c)  # evicts the oldest
    assert d.first(a)


# ---- the provider ------------------------------------------------------------------------

def test_sends_the_contract_body_and_headers(relay: FakeRelay) -> None:
    provider = start(relay)
    c = api.get_client()
    before = int(time.time() * 1000)
    c.get_boolean_value("exp-split", False, EvaluationContext("user-1"))
    c.track("purchase", EvaluationContext("user-42"), TrackingEventDetails(value=49.9))
    c.track("signup", EvaluationContext(attributes={"key": 123}))
    assert provider.tracking is not None
    provider.tracking.flush()
    after = int(time.time() * 1000)

    assert len(relay.events) == 1
    body, headers = relay.events[0], relay.event_headers[0]
    assert headers["authorization"] == KEY and headers["content-type"] == "application/json"
    assert body["sdk"] == f"python/{__version__}" == "python/0.4.0"
    assert isinstance(body["sentAt"], int) and before <= body["sentAt"] <= after
    assert all(isinstance(x["ts"], int) and before <= x["ts"] <= after for x in body["exposures"] + body["events"])
    assert sent(relay) == {
        "exposures": [{"flag": "exp-split", "variation": 0, "key": "user-1"}],
        "events": [{"event": "purchase", "key": "user-42", "value": 49.9}, {"event": "signup", "key": "123", "value": 0}],
    }


def test_nothing_is_sent_without_a_running_experiment(relay: FakeRelay) -> None:
    del relay.snap["experiments"]
    provider = start(relay)
    c = api.get_client()
    for i in range(20):
        c.get_boolean_value("exp-split", False, EvaluationContext(f"user-{i}"))
        c.track("purchase", EvaluationContext(f"user-{i}"), TrackingEventDetails(value=1))
    assert provider.tracking is not None
    provider.tracking.flush()
    assert relay.events == []

    # Experiments come and go with config updates; the current snapshot decides.
    snap = copy.deepcopy(SNAP)
    snap["version"] += 1
    relay.publish(snap, notify=False)
    assert provider.source.fetch_config()
    c.get_boolean_value("exp-split", False, EvaluationContext("user-1"))
    provider.tracking.flush()
    assert sent(relay)["exposures"] == [{"flag": "exp-split", "variation": 0, "key": "user-1"}]


def test_shutdown_flushes(relay: FakeRelay) -> None:
    start(relay)
    c = api.get_client()
    c.get_boolean_value("exp-split", False, EvaluationContext("user-1"))
    c.track("purchase", EvaluationContext("user-1"))
    assert relay.events == []
    api.shutdown()
    assert sent(relay) == {"exposures": [{"flag": "exp-split", "variation": 0, "key": "user-1"}], "events": [{"event": "purchase", "key": "user-1", "value": 0}]}


def test_events_can_be_turned_off(relay: FakeRelay) -> None:
    provider = start(relay, send_events=False)
    c = api.get_client()
    c.get_boolean_value("exp-split", False, EvaluationContext("user-1"))
    c.track("purchase", EvaluationContext("user-1"))
    api.shutdown()
    assert provider.tracking is None and relay.events == []


def test_a_429_is_dropped_without_raising(relay: FakeRelay, caplog: pytest.LogCaptureFixture) -> None:
    relay.events_status = 429
    provider = start(relay)
    c = api.get_client()
    assert provider.tracking is not None
    with caplog.at_level(logging.WARNING, logger="railflag"):
        c.track("purchase", EvaluationContext("user-1"))
        provider.tracking.flush()
        c.track("purchase", EvaluationContext("user-2"))
        provider.tracking.flush()
    assert len(relay.events) == 2  # both tried once
    assert [r.getMessage() for r in caplog.records] == ["railflag experiment events not accepted: 429"]  # once a minute
    relay.events_status = 202
    provider.tracking.flush()
    assert len(relay.events) == 2  # dropped, not retried


# ---- the sender --------------------------------------------------------------------------

class FakeTransport:
    """Stands in for `urllib.request.urlopen`."""

    def __init__(self, status: int = 202, error: Optional[Exception] = None, gate: Optional[threading.Event] = None) -> None:
        self.status, self.error, self.gate = status, error, gate
        self.requests: list[urllib.request.Request] = []

    def __call__(self, req: urllib.request.Request, timeout: float) -> Any:
        if self.gate:
            self.gate.wait(5)
        self.requests.append(req)
        if self.error:
            raise self.error
        return type("Response", (), {"status": self.status, "close": lambda self: None})()

    def bodies(self) -> list[dict[str, Any]]:
        return [json.loads(r.data) for r in self.requests]  # type: ignore[arg-type]


def sender(t: FakeTransport) -> EventSender:
    return EventSender(KEY, "https://relay.example.com/", opener=t)


def test_batches_at_1000_per_kind() -> None:
    t = FakeTransport()
    s = sender(t)
    for i in range(2500):
        s.exposure({"flag": "f", "variation": i % 2, "key": f"u-{i}"})
    for i in range(1200):
        s.event({"event": "purchase", "key": f"u-{i}", "value": i})
    s.flush()
    assert [(len(b["exposures"]), len(b["events"])) for b in t.bodies()] == [(BATCH, BATCH), (BATCH, 200), (500, 0)]
    assert [x["key"] for b in t.bodies() for x in b["exposures"]] == [f"u-{i}" for i in range(2500)]  # in order
    req = t.requests[0]
    assert (req.full_url, req.get_method()) == ("https://relay.example.com/sdk/v1/events", "POST")
    assert (req.get_header("Authorization"), req.get_header("Content-type")) == (KEY, "application/json")
    s.flush()
    assert len(t.requests) == 3  # nothing queued, nothing sent


def test_sends_an_exposure_once() -> None:
    t = FakeTransport()
    s = sender(t)
    x = {"flag": "f", "variation": 1, "key": "u"}
    s.exposure(x)  # type: ignore[arg-type]
    s.exposure(x)  # type: ignore[arg-type]
    s.flush()
    s.exposure(x)  # type: ignore[arg-type]
    s.flush()
    assert [len(b["exposures"]) for b in t.bodies()] == [1]


@pytest.mark.parametrize("transport", [
    FakeTransport(error=urllib.error.URLError("connection refused")),
    FakeTransport(error=TimeoutError("timed out")),
    FakeTransport(status=500),
], ids=["network", "timeout", "500"])
def test_failures_drop_the_batch_and_warn_at_most_once_a_minute(transport: FakeTransport, caplog: pytest.LogCaptureFixture) -> None:
    s = sender(transport)
    with caplog.at_level(logging.WARNING, logger="railflag"):
        for i in range(3):
            s.event({"event": "purchase", "key": f"u-{i}", "value": 1})
            s.flush()  # never raises
    assert len(transport.requests) == 3 and len(caplog.records) == 1
    s.flush()
    assert len(transport.requests) == 3  # dropped, not retried


def test_queues_are_capped(caplog: pytest.LogCaptureFixture) -> None:
    t = FakeTransport()
    s = sender(t)
    with caplog.at_level(logging.WARNING, logger="railflag"):
        for i in range(MAX_QUEUE + 5):
            s.event({"event": "purchase", "key": f"u-{i}", "value": 1})
            s.exposure({"flag": "f", "variation": 0, "key": f"u-{i}"})
    assert len(caplog.records) == 1 and "queue full" in caplog.records[0].getMessage()
    s.exposure({"flag": "f", "variation": 0, "key": f"u-{MAX_QUEUE + 1}"})  # dropped ones weren't marked as sent
    s.flush()
    assert sum(len(b["events"]) for b in t.bodies()) == MAX_QUEUE
    assert sum(len(b["exposures"]) for b in t.bodies()) == MAX_QUEUE
    s.exposure({"flag": "f", "variation": 0, "key": f"u-{MAX_QUEUE + 1}"})
    s.flush()
    assert t.bodies()[-1]["exposures"][0]["key"] == f"u-{MAX_QUEUE + 1}"


def test_sends_early_without_blocking_the_caller() -> None:
    gate = threading.Event()
    t = FakeTransport(gate=gate)
    s = EventSender(KEY, "https://relay.example.com", interval=60, opener=t)
    s.start()
    begun = time.monotonic()
    for i in range(600):
        s.event({"event": "purchase", "key": f"u-{i}", "value": 1})
    assert time.monotonic() - begun < 1  # the send waits on the gate; the caller doesn't
    gate.set()
    assert wait_for(lambda: len(t.requests) >= 1)
    s.stop()
    assert sum(len(b["events"]) for b in t.bodies()) == 600


def test_sends_every_interval() -> None:
    t = FakeTransport()
    s = EventSender(KEY, "https://relay.example.com", interval=0.05, opener=t)
    s.start()
    s.event({"event": "purchase", "key": "u", "value": 1})
    assert wait_for(lambda: len(t.requests) == 1)
    s.stop()
