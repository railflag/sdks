"""The Python evaluator must reproduce the TypeScript evaluator exactly. The vectors are generated
from `packages/eval` by `packages/eval/vectors/build.ts`."""

import json
import math
from pathlib import Path

import pytest

from railflag._js import js_number
from railflag._murmur3 import murmur3, murmur3_latin1
from railflag.evaluate import bucket_for, evaluate, evaluate_all

VECTORS = json.loads((Path(__file__).parents[2] / "eval" / "vectors" / "eval-vectors.json").read_text("utf-8"))


def test_hashes():
    bad = [h for h in VECTORS["hashes"] if murmur3(h["input"], h["seed"]) != h["murmur3"] or murmur3_latin1(h["input"], h["seed"]) != h["latin1"]]
    assert not bad, f"{len(bad)} of {len(VECTORS['hashes'])} hashes differ, first: {bad[0]!r}"
    assert len(VECTORS["hashes"]) > 400


def test_buckets():
    for b in VECTORS["buckets"]:
        assert bucket_for(b["flagKey"], b["salt"], b["value"], b.get("bucketing")) == b["bucket"], b


def test_every_case_matches():
    snap = VECTORS["snapshot"]
    n = 0
    for case in VECTORS["cases"]:
        got = {k: r.to_dict() for k, r in evaluate_all(snap, case["context"]).items()}
        for key, want in case["results"].items():
            assert got[key] == want, f"flag {key} with context {case['context']!r}"
            n += 1
    assert n > 5000


@pytest.mark.parametrize("case", VECTORS["typed"], ids=lambda c: f"{c['flagKey']}:{c['expectedType']}")
def test_typed(case):
    got = evaluate(VECTORS["snapshot"], case["flagKey"], case["context"], expected_type=case["expectedType"]).to_dict()
    assert got == case["result"]


@pytest.mark.parametrize("value,expected", [
    (0, "0"), (-0.0, "0"), (1.0, "1"), (1.5, "1.5"), (100, "100"), (1e21, "1e+21"), (1e-7, "1e-7"),
    (123456789012345680000.0, "123456789012345680000"), (0.000001, "0.000001"), (1.5e-10, "1.5e-10"),
    (-2.5e30, "-2.5e+30"), (10**22, "1e+22"), (math.inf, "Infinity"), (math.nan, "NaN"), (0.1 + 0.2, "0.30000000000000004"),
])
def test_js_number(value, expected):
    assert js_number(value) == expected
