package railflag

// What the provider sends for experiments, ported from packages/eval/src/experiments.ts. Both must give the
// same records for packages/eval/vectors/events-vectors.json; change them together or not at all.

import (
	"math"
	"sync"
)

// exposureRecord is one user randomized into one variation of a flag in a running experiment.
type exposureRecord struct {
	Flag      string `json:"flag"`
	Variation int    `json:"variation"`
	Key       string `json:"key"`
}

// trackRecord is one Track call that a running experiment counts.
type trackRecord struct {
	Event string  `json:"event"`
	Key   string  `json:"key"`
	Value float64 `json:"value"`
}

// experimentsOf returns the snapshot's running experiments ({"flags": [...], "events": [...]}), or nil when
// there are none.
func experimentsOf(snap Snapshot) map[string]any {
	x, _ := snap["experiments"].(map[string]any)
	return x
}

// exposureFor returns the exposure an evaluation produces, or nil. Only randomized users count: reason SPLIT,
// with a user key.
func exposureFor(experiments map[string]any, flagKey string, ctx map[string]any, r EvalResult) *exposureRecord {
	if !includesString(experiments["flags"], flagKey) {
		return nil
	}
	if r.Reason != "SPLIT" || r.VariationIndex == nil {
		return nil
	}
	key := contextKeyOf(ctx)
	if key == nil || *key == "" {
		return nil
	}
	return &exposureRecord{Flag: flagKey, Variation: *r.VariationIndex, Key: *key}
}

// trackFor returns the event a Track call produces, or nil. A non-finite value counts as 0.
func trackFor(experiments map[string]any, event string, ctx map[string]any, value float64) *trackRecord {
	if !includesString(experiments["events"], event) {
		return nil
	}
	key := contextKeyOf(ctx)
	if key == nil || *key == "" {
		return nil
	}
	if math.IsNaN(value) || math.IsInf(value, 0) {
		value = 0
	}
	return &trackRecord{Event: event, Key: *key, Value: value}
}

// maxDedupe bounds the exposures remembered per provider.
const maxDedupe = 50_000

// exposureDedupe remembers which (flag, variation, key) were already sent, so each is sent once. When full,
// the oldest entry is forgotten first. Safe for concurrent use.
type exposureDedupe struct {
	mu    sync.Mutex
	max   int
	seen  map[exposureRecord]struct{}
	order []exposureRecord // insertion order; a ring once full
	next  int              // the oldest entry once full
}

func newExposureDedupe(max int) *exposureDedupe {
	return &exposureDedupe{max: max, seen: map[exposureRecord]struct{}{}}
}

// first reports whether r is seen for the first time.
func (d *exposureDedupe) first(r exposureRecord) bool {
	d.mu.Lock()
	defer d.mu.Unlock()
	if _, ok := d.seen[r]; ok {
		return false
	}
	if len(d.order) < d.max {
		d.order = append(d.order, r)
	} else {
		delete(d.seen, d.order[d.next])
		d.order[d.next] = r
		d.next = (d.next + 1) % d.max
	}
	d.seen[r] = struct{}{}
	return true
}
