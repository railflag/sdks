package railflag

// The Go evaluator must reproduce the TypeScript evaluator exactly. The vectors are generated from
// packages/eval by packages/eval/vectors/build.ts.

import (
	"math"
	"os"
	"reflect"
	"testing"
)

func loadVectors(t *testing.T) map[string]any {
	t.Helper()
	data, err := os.ReadFile("../eval/vectors/eval-vectors.json")
	if err != nil {
		t.Skip("eval vectors not found (they live in the Railflag monorepo):", err)
	}
	v, err := parseJSON(data)
	if err != nil {
		t.Fatal(err)
	}
	return v.(map[string]any)
}

// asJSON gives a result the same JSON shape the TypeScript evaluator produces.
func asJSON(r EvalResult) map[string]any {
	out := map[string]any{"variationIndex": nil, "reason": r.Reason}
	if r.VariationIndex != nil {
		out["variationIndex"] = float64(*r.VariationIndex)
		out["value"] = r.Value
	}
	if r.RuleID != "" {
		out["ruleId"] = r.RuleID
	}
	if r.FlagVersion != nil {
		out["flagVersion"] = float64(*r.FlagVersion)
	}
	if r.ErrorCode != "" {
		out["errorCode"] = r.ErrorCode
	}
	if r.ErrorMessage != "" {
		out["errorMessage"] = r.ErrorMessage
	}
	return out
}

func TestHashes(t *testing.T) {
	hashes := loadVectors(t)["hashes"].([]any)
	if len(hashes) < 400 {
		t.Fatalf("only %d hash vectors", len(hashes))
	}
	for _, x := range hashes {
		h := x.(map[string]any)
		in, seed := h["input"].(string), uint32(h["seed"].(float64))
		if got := murmur3(in, seed); float64(got) != h["murmur3"] {
			t.Errorf("murmur3(%q, %d) = %d, want %v", in, seed, got, h["murmur3"])
		}
		if got := murmur3Latin1(in, seed); float64(got) != h["latin1"] {
			t.Errorf("murmur3Latin1(%q, %d) = %d, want %v", in, seed, got, h["latin1"])
		}
	}
}

func TestBuckets(t *testing.T) {
	for _, x := range loadVectors(t)["buckets"].([]any) {
		b := x.(map[string]any)
		bk, _ := b["bucketing"].(map[string]any)
		got := BucketFor(b["flagKey"].(string), b["salt"].(string), b["value"].(string), bk)
		if got != b["bucket"] {
			t.Errorf("BucketFor(%v) = %v, want %v", b, got, b["bucket"])
		}
	}
}

func TestEveryCaseMatches(t *testing.T) {
	v := loadVectors(t)
	snap := v["snapshot"].(map[string]any)
	n := 0
	for _, x := range v["cases"].([]any) {
		c := x.(map[string]any)
		ctx := c["context"].(map[string]any)
		got := EvaluateAll(snap, ctx)
		for key, want := range c["results"].(map[string]any) {
			if g := asJSON(got[key]); !reflect.DeepEqual(g, want) {
				t.Fatalf("flag %s with context %v:\n got  %v\n want %v", key, ctx, g, want)
			}
			n++
		}
	}
	if n < 5000 {
		t.Fatalf("only %d evaluations", n)
	}
}

func TestTyped(t *testing.T) {
	v := loadVectors(t)
	snap := v["snapshot"].(map[string]any)
	for _, x := range v["typed"].([]any) {
		c := x.(map[string]any)
		got := asJSON(Evaluate(snap, c["flagKey"].(string), c["context"].(map[string]any), c["expectedType"].(string)))
		if !reflect.DeepEqual(got, c["result"]) {
			t.Errorf("%s as %s:\n got  %v\n want %v", c["flagKey"], c["expectedType"], got, c["result"])
		}
	}
}

var tenth = 0.1 // a variable, so 0.1 + 0.2 isn't folded exactly at compile time

func TestJSNumber(t *testing.T) {
	for _, c := range []struct {
		in   float64
		want string
	}{
		{0, "0"}, {math.Copysign(0, -1), "0"}, {1, "1"}, {1.5, "1.5"}, {100, "100"}, {1e21, "1e+21"}, {1e-7, "1e-7"},
		{123456789012345680000, "123456789012345680000"}, {0.000001, "0.000001"}, {1.5e-10, "1.5e-10"},
		{-2.5e30, "-2.5e+30"}, {1e22, "1e+22"}, {math.Inf(1), "Infinity"}, {math.NaN(), "NaN"}, {tenth + 0.2, "0.30000000000000004"},
	} {
		if got := jsNumber(c.in); got != c.want {
			t.Errorf("jsNumber(%v) = %q, want %q", c.in, got, c.want)
		}
	}
}

func TestParseJSONKeepsLoneSurrogates(t *testing.T) {
	v, err := parseJSON([]byte(`["\ud800x", "\ud83d\ude00", "\u00e9"]`))
	if err != nil {
		t.Fatal(err)
	}
	got := v.([]any)
	if u := utf16Units(got[0].(string)); !reflect.DeepEqual(u, []uint16{0xD800, 'x'}) {
		t.Errorf("lone surrogate: %x", u)
	}
	if got[1] != "😀" || got[2] != "é" {
		t.Errorf("got %q", got)
	}
}

func BenchmarkEvaluate(b *testing.B) {
	data, err := os.ReadFile("../eval/vectors/eval-vectors.json")
	if err != nil {
		b.Skip(err)
	}
	v, _ := parseJSON(data)
	snap := v.(map[string]any)["snapshot"].(map[string]any)
	ctx := map[string]any{"targetingKey": "user-42", "plan": "pro", "country": "GR", "age": 42.0}
	keys := make([]string, 0)
	for k := range snap["flags"].(map[string]any) {
		keys = append(keys, k)
	}
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		Evaluate(snap, keys[i%len(keys)], ctx, "")
	}
}
