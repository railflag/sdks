package railflag

import (
	"encoding/json"
	"fmt"
	"testing"

	of "github.com/open-feature/go-sdk/openfeature"
)

func TestSendsEvaluationCountsButNoContexts(t *testing.T) {
	relay := newFakeRelay(t)
	provider := mustProvider(t, Options{SDKKey: testKey, BaseURL: relay.URL, DisableStream: true})
	if err := of.SetProviderAndWait(provider); err != nil {
		t.Fatal(err)
	}
	c := of.NewDefaultClient()
	for i := 0; i < 3; i++ {
		c.BooleanValue(bg, "new-checkout", true, of.NewEvaluationContext(fmt.Sprintf("user-%d", i), map[string]any{"plan": "free"}))
	}
	c.StringValue(bg, "nope", "d", of.EvaluationContext{}) // unknown flags aren't exposures
	provider.FlushExposures()
	provider.FlushExposures() // nothing new: nothing sent
	relay.mu.Lock()
	got, _ := json.Marshal(relay.exposures)
	relay.mu.Unlock()
	if string(got) != `[{"counts":{"new-checkout":{"0":3}}}]` {
		t.Fatalf("sent %s", got)
	}
}

func TestExposuresCanBeTurnedOff(t *testing.T) {
	relay := newFakeRelay(t)
	off := mustProvider(t, Options{SDKKey: testKey, BaseURL: relay.URL, DisableStream: true, DisableExposures: true})
	if err := of.SetProviderAndWait(off); err != nil {
		t.Fatal(err)
	}
	of.NewDefaultClient().BooleanValue(bg, "new-checkout", true, of.EvaluationContext{})
	off.FlushExposures()
	off.Shutdown()
	relay.mu.Lock()
	defer relay.mu.Unlock()
	if len(relay.exposures) != 0 {
		t.Fatalf("sent %d reports with exposures disabled", len(relay.exposures))
	}
}
