"""Keeps the latest environment snapshot in memory, like `packages/sdk-node/src/source.ts`.

Flag checks never wait on the network. Updates arrive over Server-Sent Events; polling every
`poll_interval` seconds is the safety net, and if Railflag can't be reached the last config keeps
serving. Uses only the standard library, in two daemon threads.
"""

from __future__ import annotations

import json
import logging
import socket
import threading
import urllib.error
import urllib.request
from typing import Any, Callable, Optional

from . import __version__

_log = logging.getLogger("railflag")


def _socket_of(resp: Any) -> Optional[socket.socket]:
    try:
        return resp.fp.raw._sock  # http.client.HTTPResponse -> SocketIO -> socket
    except AttributeError:
        return None


class ConfigSource:
    def __init__(
        self,
        sdk_key: str,
        base_url: str,
        stream: bool = True,
        poll_interval: float = 30.0,
        timeout: float = 10.0,
        stream_read_timeout: float = 60.0,
        logger: Optional[logging.Logger] = None,
    ) -> None:
        if not isinstance(base_url, str) or not base_url.startswith(("http://", "https://")):
            raise ValueError(f"base_url must be your Railflag relay URL, like https://relay.example.com (got {base_url!r})")
        self.sdk_key = sdk_key
        self.base_url = base_url.rstrip("/")
        self.stream = stream
        self.poll_interval = poll_interval
        self.timeout = timeout
        # The relay sends a heartbeat every 25 s, so a silent stream for this long is a dead one.
        self.stream_read_timeout = stream_read_timeout
        self.log = logger or _log
        self.snapshot: Optional[dict[str, Any]] = None
        self.streaming = False
        self._etag: Optional[str] = None
        self._listeners: list[Callable[[dict[str, Any], Optional[dict[str, Any]]], None]] = []
        self._fetch_lock = threading.Lock()
        self._stop = threading.Event()
        self._threads: list[threading.Thread] = []
        self._stream_resp: Any = None

    def on_change(self, listener: Callable[[dict[str, Any], Optional[dict[str, Any]]], None]) -> None:
        """`listener(new_snapshot, previous_snapshot)` runs on a background thread after each update."""
        self._listeners.append(listener)

    def start(self) -> None:
        """Loads the config once (raises if that fails), then keeps it fresh in the background."""
        self.fetch_config()
        self._spawn(self._poll_loop, "railflag-poll")
        if self.stream:
            self._spawn(self._stream_loop, "railflag-stream")

    def stop(self) -> None:
        self._stop.set()
        # Closing the response would wait for the reader thread, which is blocked in readline().
        # Shutting the socket down wakes it immediately.
        sock = _socket_of(self._stream_resp)
        if sock is not None:
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

    def _spawn(self, target: Callable[[], None], name: str) -> None:
        t = threading.Thread(target=target, name=name, daemon=True)
        t.start()
        self._threads.append(t)

    def _request(self, path: str, extra: Optional[dict[str, str]] = None) -> urllib.request.Request:
        headers = {"Authorization": self.sdk_key, "User-Agent": f"railflag-python/{__version__}"}
        headers.update(extra or {})
        return urllib.request.Request(f"{self.base_url}{path}", headers=headers)

    def fetch_config(self) -> bool:
        """Fetches the snapshot if it changed. Returns True when a newer one was applied."""
        with self._fetch_lock:
            extra = {"If-None-Match": self._etag} if self._etag else {}
            try:
                with urllib.request.urlopen(self._request("/sdk/v1/config", extra), timeout=self.timeout) as resp:
                    body = resp.read()
                    etag = resp.headers.get("etag")
            except urllib.error.HTTPError as e:
                if e.code == 304:
                    return False
                raise RuntimeError(f"Railflag config request failed: {e.code} {e.read()[:200]!r}") from e
            snap = json.loads(body)
            prev = self.snapshot
            if prev is not None and snap["version"] < prev["version"]:
                return False
            self.snapshot = snap
            self._etag = etag
        for listener in list(self._listeners):
            try:
                listener(snap, prev)
            except Exception:  # noqa: BLE001
                self.log.exception("railflag change listener failed")
        return True

    def _poll_loop(self) -> None:
        while not self._stop.wait(self.poll_interval):
            try:
                self.fetch_config()
            except Exception as e:  # noqa: BLE001
                self.log.warning("railflag poll failed: %s", e)

    def _stream_loop(self) -> None:
        backoff = 0.5
        while not self._stop.is_set():
            try:
                req = self._request("/sdk/v1/stream", {"Accept": "text/event-stream"})
                with urllib.request.urlopen(req, timeout=self.stream_read_timeout) as resp:
                    self._stream_resp = resp
                    self.streaming = True
                    backoff = 0.5
                    self._read_events(resp)
            except Exception as e:  # noqa: BLE001
                if not self._stop.is_set():
                    self.log.warning("railflag stream dropped, reconnecting: %s", e)
            finally:
                self.streaming = False
                self._stream_resp = None
            if self._stop.wait(backoff):
                return
            backoff = min(backoff * 2, 30.0)

    def _read_events(self, resp: Any) -> None:
        event: Optional[str] = None
        data: Optional[str] = None
        while not self._stop.is_set():
            raw = resp.readline()
            if not raw:
                return  # server closed the stream
            line = raw.decode("utf-8").rstrip("\r\n")
            if line == "":
                if event == "reconnect":
                    return
                if event == "config":
                    self._on_config_event(data or "{}")
                event, data = None, None
            elif not line.startswith(":"):
                field, _, value = line.partition(":")
                if value.startswith(" "):
                    value = value[1:]
                if field == "event":
                    event = value
                elif field == "data":
                    data = value if data is None else f"{data}\n{value}"

    def _on_config_event(self, data: str) -> None:
        try:
            version = int(json.loads(data).get("version", 0))
        except (ValueError, TypeError, AttributeError):
            return
        if self.snapshot is None or version > self.snapshot["version"]:
            try:
                self.fetch_config()
            except Exception as e:  # noqa: BLE001
                self.log.warning("railflag fetch after stream event failed: %s", e)
