"""
Sends experiment data to the relay: one exposure per user and variation of a flag in a running experiment,
and the `track()` events an experiment counts. User keys leave the process here, only for those flags and
events; Railflag hashes them before storing them. Sending runs on a background thread and never delays a
flag check, and a failed send is dropped rather than retried.

Separate from `exposures.py`, which sends anonymous per-minute counts to `/sdk/v1/exposures`.
"""

from __future__ import annotations

import json
import logging
import threading
import time
import urllib.error
import urllib.request
from typing import Any, Callable, Optional

from . import __version__
from .experiments import ExposureDedupe, ExposureRecord, TrackRecord

#: Items per request, per kind: the relay accepts up to 1,000 exposures and 1,000 events.
BATCH = 1000
#: Send early once this many items wait, so a busy process doesn't hold big queues for the whole interval.
SEND_AT = 500
#: Items kept per kind while the relay can't be reached; newer ones are dropped.
MAX_QUEUE = 10_000
#: At most one warning about failed sends per this many seconds.
WARN_EVERY = 60.0


def _now_ms() -> int:
    return int(time.time() * 1000)


class EventSender:
    def __init__(
        self,
        sdk_key: str,
        base_url: str,
        interval: float = 10.0,
        timeout: float = 5.0,
        logger: Optional[logging.Logger] = None,
        opener: Optional[Callable[..., object]] = None,
    ) -> None:
        self.sdk_key = sdk_key
        self.base_url = base_url.rstrip("/")
        self.interval = interval
        self.timeout = timeout
        self.log = logger or logging.getLogger("railflag")
        self._open = opener or urllib.request.urlopen
        self._lock = threading.Lock()  # guards the queues, the dedupe and the warning state
        self._send_lock = threading.Lock()  # one flush at a time, so requests go out in order
        self._exposures: list[dict[str, Any]] = []
        self._events: list[dict[str, Any]] = []
        self._dedupe = ExposureDedupe()
        self._warned_full = False
        self._last_warn: Optional[float] = None
        self._wake = threading.Event()
        self._stop = threading.Event()
        self._thread: Optional[threading.Thread] = None

    def exposure(self, r: ExposureRecord) -> None:
        """Queues an exposure unless this (flag, variation, key) was already sent by this provider."""
        with self._lock:
            # Check room first: an exposure dropped for lack of room isn't marked as sent.
            if self._full(self._exposures) or not self._dedupe.first(r):
                return
            self._exposures.append({**r, "ts": _now_ms()})
            self._maybe_wake()

    def event(self, r: TrackRecord) -> None:
        """Queues a track() event. Events are never de-duplicated."""
        with self._lock:
            if self._full(self._events):
                return
            self._events.append({**r, "ts": _now_ms()})
            self._maybe_wake()

    def _full(self, q: list[dict[str, Any]]) -> bool:
        if len(q) < MAX_QUEUE:
            return False
        if not self._warned_full:
            self._warned_full = True
            self.log.warning("railflag experiment events: queue full, dropping new events until the relay can be reached")
        return True

    def _maybe_wake(self) -> None:
        if len(self._exposures) + len(self._events) >= SEND_AT:
            self._wake.set()  # the sender thread flushes; the caller doesn't wait for it

    def start(self) -> None:
        self._thread = threading.Thread(target=self._loop, name="railflag-events", daemon=True)
        self._thread.start()

    def _loop(self) -> None:
        while True:
            self._wake.wait(self.interval)  # every interval, or early when the queues fill up
            self._wake.clear()
            if self._stop.is_set():
                return
            self.flush()

    def flush(self) -> None:
        """Sends everything queued, in requests of at most BATCH exposures and BATCH events."""
        with self._send_lock:
            with self._lock:
                exposures, self._exposures = self._exposures, []
                events, self._events = self._events, []
                self._warned_full = False
            for i in range(0, max(len(exposures), len(events)), BATCH):
                self._post(exposures[i : i + BATCH], events[i : i + BATCH])

    def _post(self, exposures: list[dict[str, Any]], events: list[dict[str, Any]]) -> None:
        body = json.dumps({"sentAt": _now_ms(), "sdk": f"python/{__version__}", "exposures": exposures, "events": events}).encode()
        req = urllib.request.Request(
            f"{self.base_url}/sdk/v1/events",
            data=body,
            method="POST",
            headers={"Authorization": self.sdk_key, "Content-Type": "application/json", "User-Agent": f"railflag-python/{__version__}"},
        )
        try:
            resp = self._open(req, timeout=self.timeout)
            status = getattr(resp, "status", None)
            close = getattr(resp, "close", None)
            if close:
                close()
        except urllib.error.HTTPError as e:  # urlopen raises for 4xx and 5xx
            status = e.code
        except Exception as e:  # noqa: BLE001 - experiment data is best effort
            self._warn(f"railflag experiment events not sent: {e}")
            return
        # A 404 is a relay older than experiments: nothing to warn about.
        if isinstance(status, int) and not 200 <= status < 300 and status != 404:
            self._warn(f"railflag experiment events not accepted: {status}")

    def _warn(self, message: str) -> None:
        now = time.monotonic()
        with self._lock:
            if self._last_warn is not None and now - self._last_warn < WARN_EVERY:
                return
            self._last_warn = now
        self.log.warning(message)

    def stop(self) -> None:
        """Stops the sender thread and sends what is left."""
        self._stop.set()
        self._wake.set()
        self.flush()
