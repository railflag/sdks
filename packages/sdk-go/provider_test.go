package railflag

// The provider against a fake relay that speaks the same protocol as apps/relay.

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	of "github.com/open-feature/go-sdk/openfeature"
)

const testKey = "srv_test_123"

func testFlag(key string, variations []any, over map[string]any) map[string]any {
	f := map[string]any{"key": key, "type": "boolean", "variations": variations, "salt": "s", "version": 1, "enabled": true,
		"offVariation": 0, "fallthrough": map[string]any{"variation": 1}, "targets": []any{}, "rules": []any{}, "prerequisites": []any{}}
	for k, v := range over {
		f[k] = v
	}
	return f
}

func testSnapshot(version int, checkoutOn bool) map[string]any {
	rule := func(id, attr, op string, value any) []any {
		return []any{map[string]any{"id": id, "clauses": []any{map[string]any{"attr": attr, "op": op, "values": []any{value}}}, "serve": map[string]any{"variation": 1}}}
	}
	flags := map[string]any{}
	for _, f := range []map[string]any{
		testFlag("new-checkout", []any{false, true}, map[string]any{"enabled": checkoutOn, "rules": rule("pro", "plan", "in", "pro"), "fallthrough": map[string]any{"variation": 0}}),
		testFlag("limit", []any{10, 25.0, 2.5}, map[string]any{"type": "number"}),
		testFlag("ratio", []any{0.5}, map[string]any{"type": "number", "fallthrough": map[string]any{"variation": 0}}),
		testFlag("theme", []any{map[string]any{"color": "teal"}}, map[string]any{"type": "json", "fallthrough": map[string]any{"variation": 0}}),
		testFlag("banner", []any{"off", "summer"}, map[string]any{"type": "string", "rules": rule("late", "signup", "after", "2026-01-01T00:00:00Z"), "fallthrough": map[string]any{"variation": 0}}),
	} {
		flags[f["key"].(string)] = f
	}
	return map[string]any{"env": "test", "version": version, "generatedAt": "2026-11-07T00:00:00Z", "flags": flags, "segments": map[string]any{}}
}

type fakeRelay struct {
	*httptest.Server
	mu          sync.Mutex
	snap        map[string]any
	streamOK    bool
	down        bool
	configCalls []int
	streams     map[chan string]bool
	exposures   []map[string]any
}

func newFakeRelay(t *testing.T) *fakeRelay {
	r := &fakeRelay{snap: testSnapshot(1, false), streamOK: true, streams: map[chan string]bool{}}
	r.Server = httptest.NewServer(http.HandlerFunc(r.serve))
	t.Cleanup(func() {
		of.Shutdown()
		r.closeStreams()
		r.Server.Close()
	})
	return r
}

func (r *fakeRelay) serve(w http.ResponseWriter, req *http.Request) {
	r.mu.Lock()
	down := r.down
	r.mu.Unlock()
	if down {
		http.Error(w, "down", http.StatusServiceUnavailable)
		return
	}
	if req.Header.Get("Authorization") != testKey {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	r.mu.Lock()
	version := r.snap["version"]
	body, _ := json.Marshal(r.snap)
	streamOK := r.streamOK
	r.mu.Unlock()
	switch req.URL.Path {
	case "/sdk/v1/exposures":
		var b map[string]any
		json.NewDecoder(req.Body).Decode(&b)
		r.mu.Lock()
		r.exposures = append(r.exposures, b)
		r.mu.Unlock()
		w.WriteHeader(http.StatusAccepted)
	case "/sdk/v1/config":
		etag := fmt.Sprintf(`"v%v"`, version)
		r.mu.Lock()
		defer r.mu.Unlock()
		if req.Header.Get("If-None-Match") == etag {
			r.configCalls = append(r.configCalls, 304)
			w.WriteHeader(http.StatusNotModified)
			return
		}
		r.configCalls = append(r.configCalls, 200)
		w.Header().Set("ETag", etag)
		w.Header().Set("Content-Type", "application/json")
		w.Write(body)
	case "/sdk/v1/stream":
		if !streamOK {
			http.Error(w, "{}", http.StatusInternalServerError)
			return
		}
		ch := make(chan string, 16)
		r.mu.Lock()
		r.streams[ch] = true
		r.mu.Unlock()
		defer func() {
			r.mu.Lock()
			delete(r.streams, ch)
			r.mu.Unlock()
		}()
		w.Header().Set("Content-Type", "text/event-stream")
		fmt.Fprintf(w, "id: %v\nevent: config\ndata: {\"version\":%v}\n\n", version, version)
		w.(http.Flusher).Flush()
		for {
			select {
			case <-req.Context().Done():
				return
			case msg, ok := <-ch:
				if !ok {
					return
				}
				fmt.Fprint(w, msg)
				w.(http.Flusher).Flush()
			}
		}
	default:
		http.NotFound(w, req)
	}
}

func (r *fakeRelay) publish(snap map[string]any, notify bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.snap = snap
	if notify {
		for ch := range r.streams {
			ch <- fmt.Sprintf("id: %v\nevent: config\ndata: {\"version\":%v}\n\n", snap["version"], snap["version"])
		}
	}
}

func (r *fakeRelay) closeStreams() {
	r.mu.Lock()
	defer r.mu.Unlock()
	for ch := range r.streams {
		close(ch)
		delete(r.streams, ch)
	}
}

func (r *fakeRelay) calls() []int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return slices.Clone(r.configCalls)
}

func waitFor(t *testing.T, what string, test func() bool) {
	t.Helper()
	end := time.Now().Add(5 * time.Second)
	for time.Now().Before(end) {
		if test() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("timed out waiting for %s", what)
}

func mustProvider(t *testing.T, o Options) *Provider {
	t.Helper()
	p, err := NewProvider(o)
	if err != nil {
		t.Fatal(err)
	}
	return p
}

var bg = context.Background()

func TestEvaluatesLocallyAndStreamsUpdates(t *testing.T) {
	relay := newFakeRelay(t)
	provider := mustProvider(t, Options{SDKKey: testKey, BaseURL: relay.URL})
	var mu sync.Mutex
	var changes []of.EventDetails
	cb := func(d of.EventDetails) { mu.Lock(); changes = append(changes, d); mu.Unlock() }
	var cbp of.EventCallback = &cb
	of.AddHandler(of.ProviderConfigChange, cbp)
	t.Cleanup(func() { of.RemoveHandler(of.ProviderConfigChange, cbp) })
	if err := of.SetProviderAndWait(provider); err != nil {
		t.Fatal(err)
	}
	client := of.NewDefaultClient()
	if client.State() != of.ReadyState {
		t.Fatalf("state %s", client.State())
	}
	waitFor(t, "the stream", provider.Source().Streaming)

	pro := of.NewEvaluationContext("user-1", map[string]any{"plan": "pro"})
	d, _ := client.BooleanValueDetails(bg, "new-checkout", true, pro)
	if d.Value || d.Reason != of.DisabledReason {
		t.Fatalf("got %v %s", d.Value, d.Reason)
	}

	relay.publish(testSnapshot(2, true), true)
	waitFor(t, "the update", func() bool { v, _ := client.BooleanValue(bg, "new-checkout", false, pro); return v })
	d, _ = client.BooleanValueDetails(bg, "new-checkout", false, pro)
	if d.Reason != of.TargetingMatchReason || d.Variant != "1" || d.FlagMetadata["ruleId"] != "pro" || d.FlagMetadata["configVersion"] != int64(2) {
		t.Fatalf("got %+v", d)
	}
	if v, _ := client.BooleanValue(bg, "new-checkout", true, of.NewEvaluationContext("user-1", map[string]any{"plan": "free"})); v {
		t.Fatal("free plan got the new checkout")
	}
	waitFor(t, "the change event", func() bool { mu.Lock(); defer mu.Unlock(); return len(changes) == 1 })
	if got := changes[0].FlagChanges; !slices.Equal(got, []string{"new-checkout"}) {
		t.Fatalf("flags changed: %v", got)
	}
}

func TestTypes(t *testing.T) {
	relay := newFakeRelay(t)
	if err := of.SetProviderAndWait(mustProvider(t, Options{SDKKey: testKey, BaseURL: relay.URL, DisableStream: true})); err != nil {
		t.Fatal(err)
	}
	c := of.NewDefaultClient()
	none := of.EvaluationContext{}
	if v, _ := c.IntValue(bg, "limit", 0, none); v != 25 {
		t.Errorf("limit as int = %d", v)
	}
	if v, _ := c.FloatValue(bg, "ratio", 0, none); v != 0.5 {
		t.Errorf("ratio = %v", v)
	}
	if d, _ := c.IntValueDetails(bg, "ratio", 7, none); d.Value != 7 || d.ErrorCode != of.TypeMismatchCode {
		t.Errorf("ratio as int: %+v", d)
	}
	if v, _ := c.ObjectValue(bg, "theme", nil, none); fmt.Sprint(v) != "map[color:teal]" {
		t.Errorf("theme = %v", v)
	}
	if d, _ := c.BooleanValueDetails(bg, "limit", true, none); !d.Value || d.ErrorCode != of.TypeMismatchCode {
		t.Errorf("limit as bool: %+v", d)
	}
	if d, _ := c.StringValueDetails(bg, "nope", "dflt", none); d.Value != "dflt" || d.ErrorCode != of.FlagNotFoundCode {
		t.Errorf("missing flag: %+v", d)
	}
	late := time.Date(2026, 3, 1, 0, 0, 0, 0, time.UTC)
	if v, _ := c.StringValue(bg, "banner", "x", of.NewEvaluationContext("u", map[string]any{"signup": late})); v != "summer" {
		t.Errorf("time.Time after: %s", v)
	}
	if v, _ := c.StringValue(bg, "banner", "x", of.NewEvaluationContext("u", map[string]any{"signup": "2025-12-31T00:00:00Z"})); v != "off" {
		t.Errorf("string before: %s", v)
	}
}

func TestUsesETagAndFallsBackToPolling(t *testing.T) {
	relay := newFakeRelay(t)
	relay.streamOK = false
	provider := mustProvider(t, Options{SDKKey: testKey, BaseURL: relay.URL, PollInterval: 100 * time.Millisecond})
	if err := of.SetProviderAndWait(provider); err != nil {
		t.Fatal(err)
	}
	c := of.NewDefaultClient()
	waitFor(t, "a 304", func() bool { return slices.Contains(relay.calls(), 304) })
	relay.publish(testSnapshot(5, true), false)
	pro := of.NewEvaluationContext("u", map[string]any{"plan": "pro"})
	waitFor(t, "the polled update", func() bool { v, _ := c.BooleanValue(bg, "new-checkout", false, pro); return v })
	if v := versionOf(provider.Source().Snapshot()); v != 5 {
		t.Fatalf("version %v", v)
	}
}

func TestKeepsServingWhenTheRelayGoesAway(t *testing.T) {
	relay := newFakeRelay(t)
	if err := of.SetProviderAndWait(mustProvider(t, Options{SDKKey: testKey, BaseURL: relay.URL, PollInterval: 100 * time.Millisecond})); err != nil {
		t.Fatal(err)
	}
	c := of.NewDefaultClient()
	relay.mu.Lock()
	relay.down = true
	relay.mu.Unlock()
	relay.closeStreams()
	time.Sleep(400 * time.Millisecond)
	if v, _ := c.IntValue(bg, "limit", 0, of.EvaluationContext{}); v != 25 {
		t.Fatalf("limit = %d", v)
	}
}

func TestBadKeyPutsTheProviderInErrorAndReturnsDefaults(t *testing.T) {
	relay := newFakeRelay(t)
	err := of.SetProviderAndWait(mustProvider(t, Options{SDKKey: "srv_wrong", BaseURL: relay.URL}))
	if err == nil || !strings.Contains(err.Error(), "401") {
		t.Fatalf("err = %v", err)
	}
	c := of.NewDefaultClient()
	if c.State() != of.ErrorState {
		t.Fatalf("state %s", c.State())
	}
	if d, _ := c.BooleanValueDetails(bg, "new-checkout", true, of.EvaluationContext{}); !d.Value || d.ErrorCode == "" {
		t.Fatalf("got %+v", d)
	}
}

func TestBaseURLIsRequiredAndMustBeAURL(t *testing.T) {
	for _, bad := range []string{"", "relay.example.com"} {
		if _, err := NewProvider(Options{SDKKey: testKey, BaseURL: bad}); err == nil || !strings.Contains(err.Error(), "BaseURL must be your Railflag relay URL") {
			t.Errorf("%q: %v", bad, err)
		}
	}
	if _, err := NewProvider(Options{BaseURL: "https://relay.example.com"}); err == nil {
		t.Error("missing SDKKey accepted")
	}
}

func TestNormalize(t *testing.T) {
	type Plan string
	got := toContext(of.FlattenedContext{"i": 3, "u": uint8(4), "f": float32(1.5), "p": Plan("pro"), "s": []string{"a"}, "m": map[string]int{"x": 1}, "n": (*int)(nil)})
	want := map[string]any{"i": 3.0, "u": 4.0, "f": 1.5, "p": "pro", "s": []any{"a"}, "m": map[string]any{"x": 1.0}, "n": nil}
	if fmt.Sprint(got) != fmt.Sprint(want) {
		t.Fatalf("got %v", got)
	}
}
