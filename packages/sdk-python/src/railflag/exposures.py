"""
Counts flag evaluations per flag and variation and sends the totals to the relay once a minute, so Release
Watch can see how many users got each variation. Only counts leave the process: no contexts, no user ids.
Sending never delays a flag check, and a failed send is dropped rather than retried.
"""

from __future__ import annotations

import json
import logging
import threading
import urllib.request
from typing import Callable, Optional

#: More distinct flags than this between two sends are not counted (protects memory if keys are generated).
MAX_FLAGS = 2000


class ExposureCounter:
    def __init__(
        self,
        sdk_key: str,
        base_url: str,
        interval: float = 60.0,
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
        self._lock = threading.Lock()
        self._counts: dict[str, dict[int, int]] = {}
        self._stop = threading.Event()
        self._thread: Optional[threading.Thread] = None

    def record(self, flag_key: str, variation: int) -> None:
        with self._lock:
            by_var = self._counts.get(flag_key)
            if by_var is None:
                if len(self._counts) >= MAX_FLAGS:
                    return
                by_var = self._counts[flag_key] = {}
            by_var[variation] = by_var.get(variation, 0) + 1

    def start(self) -> None:
        self._thread = threading.Thread(target=self._loop, name="railflag-exposures", daemon=True)
        self._thread.start()

    def _loop(self) -> None:
        while not self._stop.wait(self.interval):
            self.flush()

    def flush(self) -> None:
        """Sends what was counted since the last flush. Nothing is sent when nothing was evaluated."""
        with self._lock:
            counts, self._counts = self._counts, {}
        if not counts:
            return
        body = json.dumps({"counts": {k: {str(v): n for v, n in by.items()} for k, by in counts.items()}}).encode()
        req = urllib.request.Request(
            f"{self.base_url}/sdk/v1/exposures",
            data=body,
            method="POST",
            headers={"Authorization": self.sdk_key, "Content-Type": "application/json", "User-Agent": "railflag-python"},
        )
        try:
            resp = self._open(req, timeout=self.timeout)
            close = getattr(resp, "close", None)
            if close:
                close()
        except Exception as e:  # noqa: BLE001 - counts are best effort
            self.log.debug("railflag exposure counts not sent: %s", e)

    def stop(self) -> None:
        self._stop.set()
        self.flush()
