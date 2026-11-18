"""Flag evaluation, ported from `packages/eval` (TypeScript). Both must give identical results for the
vectors in `packages/eval/vectors/eval-vectors.json`; change them together or not at all."""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any, Mapping, Optional

from ._js import compare, is_number, js_regex, js_string, strict_equals, to_time
from ._murmur3 import murmur3, murmur3_latin1

WEIGHT_SCALE = 100_000

Snapshot = Mapping[str, Any]
Context = Mapping[str, Any]


@dataclass(frozen=True)
class EvalResult:
    value: Any
    variation_index: Optional[int]
    reason: str  # STATIC, DEFAULT, TARGETING_MATCH, SPLIT, DISABLED or ERROR
    rule_id: Optional[str] = None
    flag_version: Optional[int] = None
    error_code: Optional[str] = None  # FLAG_NOT_FOUND, TYPE_MISMATCH, GENERAL
    error_message: Optional[str] = None

    def to_dict(self) -> dict[str, Any]:
        """The same JSON shape the TypeScript evaluator produces."""
        out: dict[str, Any] = {"variationIndex": self.variation_index, "reason": self.reason}
        if self.variation_index is not None:
            out["value"] = self.value
        for k, v in (("ruleId", self.rule_id), ("flagVersion", self.flag_version), ("errorCode", self.error_code), ("errorMessage", self.error_message)):
            if v is not None:
                out[k] = v
        return out


# ---- bucketing ---------------------------------------------------------------------------

UNLEASH_VARIANT_SEED = 86028157

def bucket_for(flag_key: str, salt: str, value: str, bucketing: Optional[Mapping[str, Any]] = None) -> float:
    """A bucket in [0, WEIGHT_SCALE). The same inputs always give the same bucket."""
    algorithm = bucketing.get("algorithm") if bucketing else None
    if algorithm == "abtasty":
        seed = bucketing.get("seed") or ""
        return (murmur3_latin1(seed + value) % 100) * (WEIGHT_SCALE // 100)
    if algorithm == "unleash":
        group = bucketing.get("seed") or flag_key
        return (murmur3_latin1(f"{group}:{value}", 0) % 100) * (WEIGHT_SCALE // 100)
    if algorithm == "unleash-variant":
        group = bucketing.get("seed") or flag_key
        m = bucketing.get("modulus") or 1000
        return (murmur3_latin1(f"{group}:{value}", UNLEASH_VARIANT_SEED) % m) * WEIGHT_SCALE // m
    h = murmur3(f"{flag_key}.{salt}.{value}")
    return float(int((h / 0x1_0000_0000) * WEIGHT_SCALE))


def pick_weighted(weights: list[int], bucket: float, order: Optional[list[int]] = None) -> Optional[int]:
    """Ranges in variation-index order, or in `order` (variation indexes) when given."""
    total = 0
    for i in (order if order is not None else range(len(weights))):
        total += weights[i] if 0 <= i < len(weights) else 0
        if bucket < total:
            return i
    return None


# ---- clauses -----------------------------------------------------------------------------

_SEMVER = re.compile(r"^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?", re.ASCII)


def _semver(v: Any) -> Optional[tuple[int, int, int]]:
    if not isinstance(v, str):
        return None
    m = _SEMVER.match(v.strip())
    if not m:
        return None
    return int(m.group(1)), int(m.group(2) or 0), int(m.group(3) or 0)


def _match_one(op: str, actual: Any, expected: Any) -> bool:
    if op == "in":
        return strict_equals(actual, expected)
    if op == "startsWith":
        return isinstance(actual, str) and actual.startswith(js_string(expected))
    if op == "endsWith":
        return isinstance(actual, str) and actual.endswith(js_string(expected))
    if op == "contains":
        return isinstance(actual, str) and js_string(expected) in actual
    if op == "matches":
        rx = js_regex(js_string(expected)) if isinstance(actual, str) else None
        return rx is not None and rx.search(actual) is not None
    if op in ("lt", "lte", "gt", "gte"):
        c = compare(actual, expected)
        if c is None:
            return False
        return c < 0 if op == "lt" else c <= 0 if op == "lte" else c > 0 if op == "gt" else c >= 0
    if op in ("semverEq", "semverLt", "semverGt"):
        a, b = _semver(actual), _semver(expected)
        if a is None or b is None:
            return False
        return a == b if op == "semverEq" else a < b if op == "semverLt" else a > b
    if op in ("before", "after"):
        ta, tb = to_time(actual), to_time(expected)
        if ta is None or tb is None:
            return False
        return ta < tb if op == "before" else ta > tb
    return False


class _ClauseEnv:
    __slots__ = ("segments", "context_key", "depth")

    def __init__(self, segments: Mapping[str, Any], context_key: Optional[str], depth: int) -> None:
        self.segments = segments
        self.context_key = context_key
        self.depth = depth


def _clause_matches(c: Mapping[str, Any], ctx: Context, env: _ClauseEnv) -> bool:
    op = c["op"]
    negate = bool(c.get("negate"))
    if op == "segmentMatch":
        hit = any(_segment_matches(js_string(s), ctx, env) for s in c["values"])
        return not hit if negate else hit
    actual = env.context_key if c["attr"] == "key" else ctx.get(c["attr"])
    if op == "exists":
        present = actual is not None
        return not present if negate else present
    # A missing attribute never matches, negated or not.
    if actual is None:
        return False
    values = actual if isinstance(actual, (list, tuple)) else [actual]
    hit = any(_match_one(op, a, e) for a in values for e in c["values"])
    return not hit if negate else hit


def _segment_matches(key: str, ctx: Context, env: _ClauseEnv) -> bool:
    seg = env.segments.get(key)
    if not seg or env.depth > 5:
        return False
    k = env.context_key
    if k is not None:
        if k in seg.get("excluded", []):
            return False
        if k in seg.get("included", []):
            return True
    inner = _ClauseEnv(env.segments, env.context_key, env.depth + 1)
    return any(all(_clause_matches(c, ctx, inner) for c in r["clauses"]) for r in seg.get("rules", []))


# ---- evaluation --------------------------------------------------------------------------

def context_key_of(ctx: Context) -> Optional[str]:
    k = ctx.get("targetingKey")
    if k is None:
        k = ctx.get("key")
    return None if k is None else js_string(k)


def _type_of(v: Any) -> str:
    if isinstance(v, bool):
        return "boolean"
    if isinstance(v, str):
        return "string"
    if is_number(v):
        return "number"
    return "json"


_Resolved = Optional[tuple[int, str, Optional[str]]]  # (variation index, reason, rule id)


def _resolve_serve(flag: Mapping[str, Any], serve: Mapping[str, Any], ctx: Context, rule_id: Optional[str]) -> _Resolved:
    if "variation" in serve:
        return serve["variation"], "TARGETING_MATCH" if rule_id else "DEFAULT", rule_id
    rollout = serve["rollout"]
    attr = rollout.get("bucketBy") or "key"
    gate = rollout.get("gate")
    if gate:
        gate_attr = gate.get("bucketBy") or attr
        g = context_key_of(ctx) if gate_attr == "key" else ctx.get(gate_attr)
        gate_bucket = 0.0 if g is None else bucket_for(flag["key"], flag["salt"], js_string(g), gate.get("bucketing"))
        if gate_bucket >= gate["weight"]:
            return None  # outside the allocation: continue to the next rule
    raw = context_key_of(ctx) if attr == "key" else ctx.get(attr)
    # A missing bucketing value goes to bucket 0, so it is still deterministic.
    bucket = 0.0 if raw is None else bucket_for(flag["key"], flag["salt"], js_string(raw), rollout.get("bucketing"))
    index = pick_weighted(rollout["weights"], bucket, rollout.get("order"))
    if index is None:
        return None
    return index, "SPLIT", rule_id


def _evaluate_flag(snapshot: Snapshot, flag: Mapping[str, Any], ctx: Context, seen: set[str]) -> _Resolved:
    off = flag["offVariation"]
    if not flag["enabled"]:
        return off, "DISABLED", None

    for p in flag.get("prerequisites", []):
        pre = snapshot["flags"].get(p["key"])
        if not pre or p["key"] in seen:
            return off, "DEFAULT", f"prerequisite:{p['key']}"
        seen.add(p["key"])
        r = _evaluate_flag(snapshot, pre, ctx, seen)
        seen.discard(p["key"])
        if not r or not pre["enabled"] or r[0] != p["variation"]:
            return off, "DEFAULT", f"prerequisite:{p['key']}"

    key = context_key_of(ctx)
    if key is not None:
        for t in flag.get("targets", []):
            if key in t["values"]:
                return t["variation"], "TARGETING_MATCH", "target"

    env = _ClauseEnv(snapshot.get("segments", {}), key, 0)
    for rule in flag.get("rules", []):
        if not all(_clause_matches(c, ctx, env) for c in rule["clauses"]):
            continue
        r = _resolve_serve(flag, rule["serve"], ctx, rule["id"])
        if r:
            return r  # None = matched but not allocated: continue to the next rule

    return _resolve_serve(flag, flag["fallthrough"], ctx, None) or (off, "DEFAULT", None)


def evaluate(snapshot: Snapshot, flag_key: str, ctx: Optional[Context] = None, expected_type: Optional[str] = None) -> EvalResult:
    """Evaluates one flag. Never raises: problems come back as reason ERROR with an error code.

    `expected_type` is "boolean", "string", "number" or "json"; another type gives TYPE_MISMATCH.
    """
    ctx = ctx or {}
    try:
        flag = snapshot["flags"].get(flag_key)
        if not flag:
            return EvalResult(None, None, "ERROR", error_code="FLAG_NOT_FOUND", error_message=f"Flag {flag_key} not found")
        r = _evaluate_flag(snapshot, flag, ctx, {flag_key})
        index = r[0] if r else flag["offVariation"]
        variations = flag["variations"]
        version = flag.get("version")
        if index < 0 or index >= len(variations):
            return EvalResult(None, None, "ERROR", flag_version=version, error_code="GENERAL", error_message="Variation index out of range")
        value = variations[index]
        if expected_type and expected_type != "json" and _type_of(value) != expected_type:
            return EvalResult(None, None, "ERROR", flag_version=version, error_code="TYPE_MISMATCH",
                              error_message=f"Flag {flag_key} is {flag['type']}, not {expected_type}")
        return EvalResult(value, index, r[1] if r else "DEFAULT", rule_id=r[2] if r else None, flag_version=version)
    except Exception as e:  # noqa: BLE001 - evaluation must never raise into the caller
        return EvalResult(None, None, "ERROR", error_code="GENERAL", error_message=str(e))


def evaluate_all(snapshot: Snapshot, ctx: Optional[Context] = None) -> dict[str, EvalResult]:
    return {k: evaluate(snapshot, k, ctx) for k in snapshot["flags"]}
