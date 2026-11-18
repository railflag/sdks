"""End-to-end check of the Python SDK against a running Railflag (production by default).

    cd packages/sdk-python
    SY_EMAIL=you@example.com SY_PASSWORD=... uv run python examples/live_check.py

Signs in to the api, rotates the Development server key (the raw key is only shown once), creates
or reuses the flag `sdk-live-check` in Development, checks that changes stream in and that rules and
rollouts evaluate right, then switches the flag off and archives it.
"""

from __future__ import annotations

import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request
from typing import Any

from openfeature import api
from openfeature.evaluation_context import EvaluationContext
from openfeature.event import ProviderEvent

from railflag import RailflagProvider

API = os.environ.get("SY_API", "https://api.railflag.com")
RELAY = os.environ.get("SY_RELAY", "https://relay.railflag.com")
FLAG, ENV = "sdk-live-check", "development"
cookie = ""


def call(method: str, path: str, body: Any = None) -> Any:
    global cookie
    data = None if body is None else json.dumps(body).encode()
    headers = {"cookie": cookie, **({"content-type": "application/json"} if data else {})}
    req = urllib.request.Request(API + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=15) as res:
            if res.headers.get("set-cookie"):
                cookie = res.headers["set-cookie"].split(";")[0]
            text = res.read().decode()
            return json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"{method} {path} -> {e.code} {e.read().decode()[:300]}") from e


def p(rest: str) -> str:
    return f"/v1/projects/default{rest}"


def set_targeting(targeting: dict[str, Any]) -> None:
    env = next(e for e in call("GET", p(f"/flags/{FLAG}"))["environments"] if e["key"] == ENV)
    call("PUT", p(f"/flags/{FLAG}/environments/{ENV}/targeting"), {"targeting": targeting, "baseVersion": env["version"]})


def pro_rule(fallthrough: dict[str, Any], enabled: bool = True) -> dict[str, Any]:
    return {"enabled": enabled, "offVariation": 0, "fallthrough": fallthrough, "targets": [], "prerequisites": [],
            "rules": [{"id": "pro", "name": "Pro plan", "clauses": [{"attr": "plan", "op": "in", "values": ["pro"]}], "serve": {"variation": 1}}]}


failures = 0


def check(label: str, ok: bool, detail: str = "") -> None:
    global failures
    failures += 0 if ok else 1
    print(f"{'PASS' if ok else 'FAIL'}  {label}{f'  ({detail})' if detail else ''}")


def wait_for(test: Any, timeout: float = 10.0) -> int:
    t0 = time.perf_counter()
    while time.perf_counter() - t0 < timeout:
        if test():
            return round((time.perf_counter() - t0) * 1000)
        time.sleep(0.005)
    return -1


email, password = os.environ.get("SY_EMAIL"), os.environ.get("SY_PASSWORD")
if not email or not password:
    sys.exit("Set SY_EMAIL and SY_PASSWORD")
call("POST", "/v1/auth/login", {"email": email, "password": password})
server_key = call("POST", p(f"/environments/{ENV}/keys/server/rotate"))["key"]
print(f"signed in; {ENV} server key rotated ({server_key[:12]}…)")
try:
    call("POST", p("/flags"), {"key": FLAG, "name": "SDK live check", "type": "boolean", "description": "Created by the SDK live checks"})
    print(f"created flag {FLAG}")
except RuntimeError as e:
    if "409" not in str(e):
        raise
    call("PATCH", p(f"/flags/{FLAG}"), {"archived": False})
    print(f"reusing flag {FLAG}")
set_targeting({**pro_rule({"variation": 1}, enabled=False), "rules": []})

print("\nPython SDK (railflag-openfeature, local evaluation)")
provider = RailflagProvider(sdk_key=server_key, base_url=RELAY)
changed = threading.Event()
api.add_handler(ProviderEvent.PROVIDER_CONFIGURATION_CHANGED, lambda d: changed.set())
t0 = time.perf_counter()
api.set_provider_and_wait(provider)
client = api.get_client()
check("provider ready", True, f"{round((time.perf_counter() - t0) * 1000)} ms, config v{provider.source.snapshot['version']}")
check("SSE stream connected", wait_for(lambda: provider.source.streaming, 5) >= 0)

user = EvaluationContext("user-1")
d = client.get_boolean_details(FLAG, True, user)
check("flag off -> False", d.value is False, f"reason {d.reason}")

call("POST", p(f"/flags/{FLAG}/environments/{ENV}/toggle"), {"enabled": True})
ms = wait_for(lambda: client.get_boolean_value(FLAG, False, user) is True)
check("toggle on reaches the SDK over SSE", ms >= 0, f"{ms} ms after the api answered")
check("PROVIDER_CONFIGURATION_CHANGED fired", changed.wait(2))

set_targeting(pro_rule({"variation": 0}))
ms = wait_for(lambda: client.get_boolean_value(FLAG, True, EvaluationContext("user-1", {"plan": "free"})) is False)
check("rule change arrives", ms >= 0, f"{ms} ms")
d = client.get_boolean_details(FLAG, False, EvaluationContext("user-1", {"plan": "pro"}))
check("plan=pro -> True via rule", d.value is True and d.reason == "TARGETING_MATCH", f"reason {d.reason}, rule {d.flag_metadata.get('ruleId')}")

version = provider.source.snapshot["version"]
set_targeting(pro_rule({"rollout": {"weights": [70_000, 30_000]}}))
wait_for(lambda: provider.source.snapshot["version"] > version)
n, on = 10_000, 0
t1 = time.perf_counter()
for i in range(n):
    on += client.get_boolean_value(FLAG, False, EvaluationContext(f"user-{i}", {"plan": "free"}))
per = (time.perf_counter() - t1) * 1e6 / n
check("30% rollout over 10,000 users", abs(on / n - 0.3) < 0.02, f"{on / n:.1%} True, {per:.0f} µs per evaluation through OpenFeature")

# The relay evaluates for browsers with the TypeScript evaluator; both must put every user in the same bucket.
client_key = next(e for e in call("GET", p("/environments"))["environments"] if e["key"] == ENV)["clientKey"]
mismatch = 0
for i in range(200):
    ctx = {"targetingKey": f"user-{i}", "plan": "free"}
    req = urllib.request.Request(f"{RELAY}/sdk/v1/evaluate", data=json.dumps({"context": ctx}).encode(), method="POST",
                                 headers={"authorization": client_key, "content-type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as res:
        remote = json.loads(res.read())["flags"][FLAG]["value"]
    if remote != client.get_boolean_value(FLAG, not remote, EvaluationContext(f"user-{i}", {"plan": "free"})):
        mismatch += 1
check("Python and the relay (TypeScript) agree for 200 users", mismatch == 0, f"{mismatch} differ")

api.shutdown()
call("POST", p(f"/flags/{FLAG}/environments/{ENV}/toggle"), {"enabled": False})
call("PATCH", p(f"/flags/{FLAG}"), {"archived": True})
print(f"\n{FLAG} switched off and archived. {f'{failures} check(s) failed' if failures else 'All checks passed.'}")
sys.exit(1 if failures else 0)
