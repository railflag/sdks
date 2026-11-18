package railflag

// Flag evaluation, ported from packages/eval (TypeScript). Both must give identical results for
// the vectors in packages/eval/vectors/eval-vectors.json; change them together or not at all.

import (
	"fmt"
	"math"
	"regexp"
	"strconv"
	"unicode/utf8"
)

// WeightScale is the number of buckets a rollout is spread over.
const WeightScale = 100_000

const unleashVariantSeed = 86028157

// Snapshot is an environment's flag config as the relay serves it (GET /sdk/v1/config).
type Snapshot = map[string]any

// EvalResult is the outcome of one flag evaluation.
type EvalResult struct {
	Value          any
	VariationIndex *int   // nil when Reason is ERROR
	Reason         string // STATIC, DEFAULT, TARGETING_MATCH, SPLIT, DISABLED or ERROR
	RuleID         string
	FlagVersion    *int
	ErrorCode      string // FLAG_NOT_FOUND, TYPE_MISMATCH or GENERAL
	ErrorMessage   string
}

// ---- bucketing --------------------------------------------------------------------------

// BucketFor returns a bucket in [0, WeightScale). The same inputs always give the same bucket.
func BucketFor(flagKey, salt, value string, bucketing map[string]any) float64 {
	algorithm, _ := bucketing["algorithm"].(string)
	seed, hasSeed := bucketing["seed"].(string)
	switch algorithm {
	case "abtasty":
		return float64(murmur3Latin1(seed+value, 0)%100) * (WeightScale / 100)
	case "unleash":
		if !hasSeed {
			seed = flagKey
		}
		return float64(murmur3Latin1(seed+":"+value, 0)%100) * (WeightScale / 100)
	case "unleash-variant":
		if !hasSeed {
			seed = flagKey
		}
		m := uint32(1000)
		if mod, ok := bucketing["modulus"].(float64); ok {
			m = uint32(mod)
		}
		return math.Floor(float64(murmur3Latin1(seed+":"+value, unleashVariantSeed)%m) * WeightScale / float64(m))
	}
	h := murmur3(flagKey+"."+salt+"."+value, 0)
	return math.Floor(float64(h) / 0x1_0000_0000 * WeightScale)
}

// pickWeighted lays out cumulative ranges in variation-index order, or in `order` when given, and
// returns the variation whose range holds the bucket (ok false when unallocated).
func pickWeighted(weights []any, bucket float64, order []any) (int, bool) {
	total := 0.0
	try := func(i int) bool {
		if i >= 0 && i < len(weights) {
			w, _ := weights[i].(float64)
			total += w
		}
		return bucket < total
	}
	if order != nil {
		for _, o := range order {
			i := toInt(o)
			if try(i) {
				return i, true
			}
		}
		return 0, false
	}
	for i := range weights {
		if try(i) {
			return i, true
		}
	}
	return 0, false
}

func toInt(v any) int {
	f, ok := v.(float64)
	if !ok {
		panic(fmt.Sprintf("expected a number, got %T", v))
	}
	return int(f)
}

// ---- clauses ----------------------------------------------------------------------------

var semverRe = regexp.MustCompile(`^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?`)

func parseSemver(v any) ([3]float64, bool) {
	s, ok := v.(string)
	if !ok {
		return [3]float64{}, false
	}
	m := semverRe.FindStringSubmatch(trimJS(s))
	if m == nil {
		return [3]float64{}, false
	}
	var out [3]float64
	for i := 0; i < 3; i++ {
		if m[i+1] != "" {
			out[i], _ = strconv.ParseFloat(m[i+1], 64)
		}
	}
	return out, true
}

// trimJS is String.prototype.trim: JavaScript whitespace and line terminators.
func trimJS(s string) string {
	isSpace := func(r rune) bool {
		switch r {
		case '\t', '\n', '\v', '\f', '\r', ' ', 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000, 0xFEFF:
			return true
		}
		return r >= 0x2000 && r <= 0x200A
	}
	start, end := 0, len(s)
	for start < end {
		r, size := utf8.DecodeRuneInString(s[start:])
		if !isSpace(r) {
			break
		}
		start += size
	}
	for end > start {
		r, size := utf8.DecodeLastRuneInString(s[start:end])
		if !isSpace(r) {
			break
		}
		end -= size
	}
	return s[start:end]
}

func cmpSemver(a, b [3]float64) int {
	for i := 0; i < 3; i++ {
		if a[i] != b[i] {
			if a[i] < b[i] {
				return -1
			}
			return 1
		}
	}
	return 0
}

func matchOne(op string, actual, expected any) bool {
	switch op {
	case "in":
		return strictEquals(actual, expected)
	case "startsWith":
		s, ok := actual.(string)
		return ok && hasPrefix(s, jsString(expected))
	case "endsWith":
		s, ok := actual.(string)
		return ok && hasSuffix(s, jsString(expected))
	case "contains":
		s, ok := actual.(string)
		return ok && contains(s, jsString(expected))
	case "matches":
		s, ok := actual.(string)
		if !ok {
			return false
		}
		re := jsRegex(jsString(expected))
		return re != nil && re.MatchString(s)
	case "lt", "lte", "gt", "gte":
		c, ok := compare(actual, expected)
		if !ok || math.IsNaN(c) {
			return false
		}
		switch op {
		case "lt":
			return c < 0
		case "lte":
			return c <= 0
		case "gt":
			return c > 0
		default:
			return c >= 0
		}
	case "semverEq", "semverLt", "semverGt":
		a, okA := parseSemver(actual)
		b, okB := parseSemver(expected)
		if !okA || !okB {
			return false
		}
		c := cmpSemver(a, b)
		switch op {
		case "semverEq":
			return c == 0
		case "semverLt":
			return c < 0
		default:
			return c > 0
		}
	case "before", "after":
		ta, okA := toTime(actual)
		tb, okB := toTime(expected)
		if !okA || !okB {
			return false
		}
		if op == "before" {
			return ta < tb
		}
		return ta > tb
	}
	return false
}

type clauseEnv struct {
	segments   map[string]any
	contextKey *string
	depth      int
}

// clauseMatches: a clause matches when any value matches (OR). `negate` inverts the result,
// except that a missing attribute never matches, negated or not. `exists` is about presence.
func clauseMatches(c map[string]any, ctx map[string]any, env clauseEnv) bool {
	op, _ := c["op"].(string)
	negate := truthy(c["negate"])
	values, _ := c["values"].([]any)
	if op == "segmentMatch" {
		hit := false
		for _, s := range values {
			if segmentMatches(jsString(s), ctx, env) {
				hit = true
				break
			}
		}
		return hit != negate
	}
	var actual any
	if attr, _ := c["attr"].(string); attr == "key" {
		if env.contextKey != nil {
			actual = *env.contextKey
		}
	} else {
		actual = ctx[attr]
	}
	if op == "exists" {
		return (actual != nil) != negate
	}
	if actual == nil {
		return false
	}
	actuals, isList := actual.([]any)
	if !isList {
		actuals = []any{actual}
	}
	hit := false
outer:
	for _, a := range actuals {
		for _, e := range values {
			if matchOne(op, a, e) {
				hit = true
				break outer
			}
		}
	}
	return hit != negate
}

func segmentMatches(key string, ctx map[string]any, env clauseEnv) bool {
	seg, _ := env.segments[key].(map[string]any)
	if seg == nil || env.depth > 5 {
		return false
	}
	if k := env.contextKey; k != nil {
		if includesString(seg["excluded"], *k) {
			return false
		}
		if includesString(seg["included"], *k) {
			return true
		}
	}
	inner := clauseEnv{segments: env.segments, contextKey: env.contextKey, depth: env.depth + 1}
	rules, _ := seg["rules"].([]any)
	for _, r := range rules {
		if allClauses(r.(map[string]any)["clauses"], ctx, inner) {
			return true
		}
	}
	return false
}

func allClauses(clauses any, ctx map[string]any, env clauseEnv) bool {
	list, _ := clauses.([]any)
	for _, c := range list {
		if !clauseMatches(c.(map[string]any), ctx, env) {
			return false
		}
	}
	return true
}

func includesString(list any, s string) bool {
	items, _ := list.([]any)
	for _, x := range items {
		if v, ok := x.(string); ok && v == s {
			return true
		}
	}
	return false
}

func truthy(v any) bool {
	switch t := v.(type) {
	case nil:
		return false
	case bool:
		return t
	case float64:
		return t != 0 && !math.IsNaN(t)
	case string:
		return t != ""
	}
	return true
}

// ---- evaluation -------------------------------------------------------------------------

func contextKeyOf(ctx map[string]any) *string {
	k := ctx["targetingKey"]
	if k == nil {
		k = ctx["key"]
	}
	if k == nil {
		return nil
	}
	s := jsString(k)
	return &s
}

func typeOf(v any) string {
	switch v.(type) {
	case bool:
		return "boolean"
	case string:
		return "string"
	case float64:
		return "number"
	}
	return "json"
}

type resolved struct {
	index  int
	reason string
	ruleID string
}

func attrValue(ctx map[string]any, attr string) any {
	if attr == "key" {
		if k := contextKeyOf(ctx); k != nil {
			return *k
		}
		return nil
	}
	return ctx[attr]
}

func resolveServe(flag map[string]any, serve map[string]any, ctx map[string]any, ruleID string) *resolved {
	if v, ok := serve["variation"]; ok {
		reason := "DEFAULT"
		if ruleID != "" {
			reason = "TARGETING_MATCH"
		}
		return &resolved{toInt(v), reason, ruleID}
	}
	rollout := serve["rollout"].(map[string]any)
	attr := "key"
	if b, ok := rollout["bucketBy"].(string); ok {
		attr = b
	}
	flagKey, _ := flag["key"].(string)
	salt, _ := flag["salt"].(string)
	if gate, _ := rollout["gate"].(map[string]any); gate != nil {
		gateAttr := attr
		if b, ok := gate["bucketBy"].(string); ok {
			gateAttr = b
		}
		gateBucket := 0.0
		if g := attrValue(ctx, gateAttr); g != nil {
			bk, _ := gate["bucketing"].(map[string]any)
			gateBucket = BucketFor(flagKey, salt, jsString(g), bk)
		}
		if w, ok := gate["weight"].(float64); ok && gateBucket >= w {
			return nil // outside the allocation: continue to the next rule
		}
	}
	// A missing bucketing value goes to bucket 0, so it is still deterministic.
	bucket := 0.0
	if raw := attrValue(ctx, attr); raw != nil {
		bk, _ := rollout["bucketing"].(map[string]any)
		bucket = BucketFor(flagKey, salt, jsString(raw), bk)
	}
	weights, _ := rollout["weights"].([]any)
	order, _ := rollout["order"].([]any)
	index, ok := pickWeighted(weights, bucket, order)
	if !ok {
		return nil
	}
	return &resolved{index, "SPLIT", ruleID}
}

func evaluateFlag(snap Snapshot, flag map[string]any, ctx map[string]any, seen map[string]bool) *resolved {
	off := toInt(flag["offVariation"])
	if enabled, _ := flag["enabled"].(bool); !enabled {
		return &resolved{off, "DISABLED", ""}
	}

	flags, _ := snap["flags"].(map[string]any)
	prereqs, _ := flag["prerequisites"].([]any)
	for _, x := range prereqs {
		p := x.(map[string]any)
		pk, _ := p["key"].(string)
		pre, _ := flags[pk].(map[string]any)
		if pre == nil || seen[pk] {
			return &resolved{off, "DEFAULT", "prerequisite:" + pk}
		}
		seen[pk] = true
		r := evaluateFlag(snap, pre, ctx, seen)
		delete(seen, pk)
		preEnabled, _ := pre["enabled"].(bool)
		if r == nil || !preEnabled || float64(r.index) != p["variation"] {
			return &resolved{off, "DEFAULT", "prerequisite:" + pk}
		}
	}

	key := contextKeyOf(ctx)
	if key != nil {
		targets, _ := flag["targets"].([]any)
		for _, x := range targets {
			t := x.(map[string]any)
			if includesString(t["values"], *key) {
				return &resolved{toInt(t["variation"]), "TARGETING_MATCH", "target"}
			}
		}
	}

	segments, _ := snap["segments"].(map[string]any)
	env := clauseEnv{segments: segments, contextKey: key}
	rules, _ := flag["rules"].([]any)
	for _, x := range rules {
		rule := x.(map[string]any)
		if !allClauses(rule["clauses"], ctx, env) {
			continue
		}
		id, _ := rule["id"].(string)
		if r := resolveServe(flag, rule["serve"].(map[string]any), ctx, id); r != nil {
			return r // nil = matched but not allocated: continue to the next rule
		}
	}

	if r := resolveServe(flag, flag["fallthrough"].(map[string]any), ctx, ""); r != nil {
		return r
	}
	return &resolved{off, "DEFAULT", ""}
}

// Evaluate evaluates one flag. It never panics: problems come back as Reason "ERROR" with an
// ErrorCode. expectedType is "", "boolean", "string", "number" or "json"; a variation of another
// type gives TYPE_MISMATCH. ctx holds normalized values (see normalize): float64, string, bool,
// nil, []any and map[string]any.
func Evaluate(snap Snapshot, flagKey string, ctx map[string]any, expectedType string) (res EvalResult) {
	if ctx == nil {
		ctx = map[string]any{}
	}
	var version *int
	defer func() {
		if p := recover(); p != nil {
			res = EvalResult{Reason: "ERROR", ErrorCode: "GENERAL", ErrorMessage: fmt.Sprint(p), FlagVersion: version}
		}
	}()
	flags, _ := snap["flags"].(map[string]any)
	flag, _ := flags[flagKey].(map[string]any)
	if flag == nil {
		return EvalResult{Reason: "ERROR", ErrorCode: "FLAG_NOT_FOUND", ErrorMessage: "Flag " + flagKey + " not found"}
	}
	if v, ok := flag["version"].(float64); ok {
		n := int(v)
		version = &n
	}
	r := evaluateFlag(snap, flag, ctx, map[string]bool{flagKey: true})
	variations, _ := flag["variations"].([]any)
	if r.index < 0 || r.index >= len(variations) {
		return EvalResult{Reason: "ERROR", ErrorCode: "GENERAL", ErrorMessage: "Variation index out of range", FlagVersion: version}
	}
	value := variations[r.index]
	if expectedType != "" && expectedType != "json" && typeOf(value) != expectedType {
		return EvalResult{Reason: "ERROR", ErrorCode: "TYPE_MISMATCH", FlagVersion: version,
			ErrorMessage: fmt.Sprintf("Flag %s is %s, not %s", flagKey, jsString(flag["type"]), expectedType)}
	}
	index := r.index
	return EvalResult{Value: value, VariationIndex: &index, Reason: r.reason, RuleID: r.ruleID, FlagVersion: version}
}

// EvaluateAll evaluates every flag in the snapshot.
func EvaluateAll(snap Snapshot, ctx map[string]any) map[string]EvalResult {
	flags, _ := snap["flags"].(map[string]any)
	out := make(map[string]EvalResult, len(flags))
	for k := range flags {
		out[k] = Evaluate(snap, k, ctx, "")
	}
	return out
}
