"""The provider against a fake relay that speaks the same protocol as `apps/relay`."""

from __future__ import annotations

import json
import queue
import threading
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

import pytest
from openfeature import api
from openfeature.evaluation_context import EvaluationContext
from openfeature.event import ProviderEvent
from openfeature.exception import ErrorCode
from openfeature.provider import ProviderStatus

from railflag import RailflagProvider

KEY = "srv_test_123"


def flag(key: str, variations: list[Any], **over: Any) -> dict[str, Any]:
    f = {"key": key, "type": "boolean", "variations": variations, "salt": "s", "version": 1, "enabled": True, "offVariation": 0,
         "fallthrough": {"variation": 1}, "targets": [], "rules": [], "prerequisites": []}
    f.update(over)
    return f


def snapshot(version: int, checkout_on: bool) -> dict[str, Any]:
    flags = [
        flag("new-checkout", [False, True], enabled=checkout_on,
             rules=[{"id": "pro", "clauses": [{"attr": "plan", "op": "in", "values": ["pro"]}], "serve": {"variation": 1}}],
             fallthrough={"variation": 0}),
        flag("limit", [10, 25.0, 2.5], type="number", fallthrough={"variation": 1}),
        flag("ratio", [0.5], type="number", fallthrough={"variation": 0}),
        flag("theme", [{"color": "teal"}], type="json", fallthrough={"variation": 0}),
        flag("banner", ["off", "summer"], type="string",
             rules=[{"id": "late", "clauses": [{"attr": "signup", "op": "after", "values": ["2026-01-01T00:00:00Z"]}], "serve": {"variation": 1}}],
             fallthrough={"variation": 0}),
    ]
    return {"env": "test", "version": version, "generatedAt": "2026-11-07T00:00:00Z", "flags": {f["key"]: f for f in flags}, "segments": {}}


class FakeRelay:
    def __init__(self) -> None:
        self.snap = snapshot(1, False)
        self.stream_ok = True
        self.config_calls: list[int] = []  # status codes served
        self.streams: list[queue.Queue[str | None]] = []
        self.exposures: list[dict[str, Any]] = []
        relay = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *a: Any) -> None:
                pass

            def do_GET(self) -> None:  # noqa: N802
                if self.headers.get("Authorization") != KEY:
                    return self._send(401, b'{"error":"unauthorized"}')
                if self.path == "/sdk/v1/config":
                    etag = f'"v{relay.snap["version"]}"'
                    if self.headers.get("If-None-Match") == etag:
                        relay.config_calls.append(304)
                        self.send_response(304)
                        self.send_header("Content-Length", "0")
                        self.end_headers()
                        return
                    relay.config_calls.append(200)
                    return self._send(200, json.dumps(relay.snap).encode(), etag)
                if self.path == "/sdk/v1/stream":
                    if not relay.stream_ok:
                        return self._send(500, b"{}")
                    q: queue.Queue[str | None] = queue.Queue()
                    relay.streams.append(q)
                    self.send_response(200)
                    self.send_header("Content-Type", "text/event-stream")
                    self.end_headers()
                    try:
                        self.wfile.write(f'id: {relay.snap["version"]}\nevent: config\ndata: {{"version":{relay.snap["version"]}}}\n\n'.encode())
                        self.wfile.flush()
                        while True:
                            msg = q.get()
                            if msg is None:
                                return
                            self.wfile.write(msg.encode())
                            self.wfile.flush()
                    except (BrokenPipeError, ConnectionResetError):
                        return
                    finally:
                        relay.streams.remove(q)
                self._send(404, b"{}")

            def do_POST(self) -> None:  # noqa: N802
                body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                if self.path == "/sdk/v1/exposures" and self.headers.get("Authorization") == KEY:
                    relay.exposures.append(json.loads(body))
                    return self._send(202, b'{"ok":true}')
                self._send(404, b"{}")

            def _send(self, code: int, body: bytes, etag: str | None = None) -> None:
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                if etag:
                    self.send_header("ETag", etag)
                self.end_headers()
                self.wfile.write(body)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"

    def publish(self, snap: dict[str, Any], notify: bool = True) -> None:
        self.snap = snap
        if notify:
            for q in list(self.streams):
                q.put(f'id: {snap["version"]}\nevent: config\ndata: {{"version":{snap["version"]}}}\n\n')

    def close(self) -> None:
        for q in list(self.streams):
            q.put(None)
        self.server.shutdown()
        self.server.server_close()


def wait_for(test: Any, timeout: float = 5.0) -> bool:
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if test():
            return True
        time.sleep(0.01)
    return False


@pytest.fixture
def relay():
    r = FakeRelay()
    yield r
    api.shutdown()
    api.clear_providers()
    r.close()


def test_evaluates_locally_and_streams_updates(relay: FakeRelay) -> None:
    provider = RailflagProvider(sdk_key=KEY, base_url=relay.url)
    changes: list[Any] = []
    api.add_handler(ProviderEvent.PROVIDER_CONFIGURATION_CHANGED, lambda d: changes.append(d))
    api.set_provider_and_wait(provider)
    client = api.get_client()
    assert client.get_provider_status() == ProviderStatus.READY
    assert wait_for(lambda: provider.source.streaming)

    pro = EvaluationContext("user-1", {"plan": "pro"})
    d = client.get_boolean_details("new-checkout", True, pro)
    assert (d.value, d.reason) == (False, "DISABLED")

    relay.publish(snapshot(2, True))
    assert wait_for(lambda: client.get_boolean_value("new-checkout", False, pro) is True)
    d = client.get_boolean_details("new-checkout", False, pro)
    assert (d.reason, d.variant, d.flag_metadata["ruleId"], d.flag_metadata["configVersion"]) == ("TARGETING_MATCH", "1", "pro", 2)
    assert client.get_boolean_value("new-checkout", True, EvaluationContext("user-1", {"plan": "free"})) is False
    assert wait_for(lambda: len(changes) == 1)
    assert changes[0].flags_changed == ["new-checkout"]


def test_types(relay: FakeRelay) -> None:
    api.set_provider_and_wait(RailflagProvider(sdk_key=KEY, base_url=relay.url, stream=False))
    c = api.get_client()
    assert c.get_integer_value("limit", 0) == 25 and isinstance(c.get_integer_value("limit", 0), int)
    assert c.get_float_value("limit", 0.0) == 25.0
    assert c.get_float_value("ratio", 0.0) == 0.5
    d = c.get_integer_details("ratio", 7)
    assert (d.value, d.error_code) == (7, ErrorCode.TYPE_MISMATCH)
    assert c.get_object_value("theme", {}) == {"color": "teal"}
    d = c.get_boolean_details("limit", True)
    assert (d.value, d.error_code) == (True, ErrorCode.TYPE_MISMATCH)
    d = c.get_string_details("nope", "dflt")
    assert (d.value, d.error_code) == ("dflt", ErrorCode.FLAG_NOT_FOUND)
    late = datetime(2026, 3, 1, tzinfo=timezone.utc)
    assert c.get_string_value("banner", "x", EvaluationContext("u", {"signup": late})) == "summer"
    assert c.get_string_value("banner", "x", EvaluationContext("u", {"signup": "2025-12-31T00:00:00Z"})) == "off"


def test_uses_etag_and_falls_back_to_polling(relay: FakeRelay) -> None:
    relay.stream_ok = False
    provider = RailflagProvider(sdk_key=KEY, base_url=relay.url, poll_interval=0.1)
    api.set_provider_and_wait(provider)
    c = api.get_client()
    assert wait_for(lambda: 304 in relay.config_calls)
    relay.publish(snapshot(5, True), notify=False)
    assert wait_for(lambda: c.get_boolean_value("new-checkout", False, EvaluationContext("u", {"plan": "pro"})) is True)
    assert provider.source.snapshot["version"] == 5


def test_keeps_serving_when_the_relay_goes_away(relay: FakeRelay) -> None:
    api.set_provider_and_wait(RailflagProvider(sdk_key=KEY, base_url=relay.url, poll_interval=0.1))
    c = api.get_client()
    relay.close()
    time.sleep(0.4)
    assert c.get_integer_value("limit", 0) == 25


def test_bad_key_puts_the_provider_in_error_and_returns_defaults(relay: FakeRelay) -> None:
    with pytest.raises(Exception, match="401"):
        api.set_provider_and_wait(RailflagProvider(sdk_key="srv_wrong", base_url=relay.url))
    c = api.get_client()
    assert c.get_provider_status() == ProviderStatus.ERROR
    d = c.get_boolean_details("new-checkout", True)
    assert d.value is True and d.error_code is not None


def test_base_url_is_required_and_must_be_a_url() -> None:
    with pytest.raises(TypeError):
        RailflagProvider(sdk_key=KEY)  # type: ignore[call-arg]
    for bad in ("", "relay.example.com", None):
        with pytest.raises(ValueError, match="base_url must be your Railflag relay URL"):
            RailflagProvider(sdk_key=KEY, base_url=bad)  # type: ignore[arg-type]


def test_sends_evaluation_counts_but_no_contexts(relay: FakeRelay) -> None:
    provider = RailflagProvider(sdk_key=KEY, base_url=relay.url, stream=False, exposure_interval=60)
    api.set_provider_and_wait(provider)
    c = api.get_client()
    for i in range(3):
        c.get_boolean_value("new-checkout", True, EvaluationContext(f"user-{i}", {"plan": "free", "email": "x@y.z"}))
    c.get_string_value("nope", "d")  # unknown flags aren't exposures
    assert provider.exposures is not None
    provider.exposures.flush()
    assert relay.exposures == [{"counts": {"new-checkout": {"0": 3}}}]
    assert "user-" not in json.dumps(relay.exposures)
    provider.exposures.flush()
    assert len(relay.exposures) == 1  # nothing new, nothing sent
    c.get_boolean_value("new-checkout", True)
    api.shutdown()  # flushes on shutdown
    assert len(relay.exposures) == 2


def test_exposures_can_be_turned_off(relay: FakeRelay) -> None:
    provider = RailflagProvider(sdk_key=KEY, base_url=relay.url, stream=False, send_exposures=False)
    api.set_provider_and_wait(provider)
    api.get_client().get_boolean_value("new-checkout", True)
    api.shutdown()
    assert provider.exposures is None and relay.exposures == []
