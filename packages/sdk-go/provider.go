// Package railflag is Railflag's OpenFeature provider for Go. It downloads your environment's flag
// config once, evaluates every flag check in process, and picks up changes within a second over
// Server-Sent Events.
//
//	provider, err := railflag.NewProvider(railflag.Options{
//		SDKKey:  os.Getenv("RAILFLAG_SERVER_KEY"),
//		BaseURL: "https://relay.railflag.com",
//	})
//	if err != nil { ... }
//	if err := openfeature.SetProviderAndWait(provider); err != nil { ... }
//	client := openfeature.NewDefaultClient()
//	on, _ := client.BooleanValue(ctx, "new-checkout", false,
//		openfeature.NewEvaluationContext("user-42", map[string]any{"plan": "pro"}))
package railflag

import (
	"context"
	"fmt"
	"log/slog"
	"math"
	"net/http"
	"reflect"
	"slices"
	"strconv"
	"time"

	of "github.com/open-feature/go-sdk/openfeature"
)

// Options configure a Provider. SDKKey and BaseURL are required.
type Options struct {
	SDKKey        string        // a server key: Environments → keys
	BaseURL       string        // your Railflag relay, like https://relay.railflag.com
	DisableStream bool          // poll only, without the Server-Sent Events stream
	PollInterval  time.Duration // default 30 s
	Timeout       time.Duration // per config request, default 10 s
	HTTPClient    *http.Client  // default &http.Client{}
	Logger        *slog.Logger  // default slog.Default()
	// DisableExposures stops sending evaluation counts. By default the provider sends, once a minute, how many
	// times each flag returned each variation, so Release Watch can measure rollouts. Counts only: no
	// contexts or user ids (for experiment events, see DisableEvents).
	DisableExposures bool
	ExposureInterval time.Duration // default 1 minute
	// DisableEvents stops sending experiment events. While an experiment runs in your environment, the provider
	// sends which variation each user got of the experiment's flags (once per user and variation) and the Track
	// calls the experiment counts. It sends user keys only for flags in a running experiment and only for events
	// an experiment counts, and nothing at all when no experiment is running. Railflag hashes the keys before
	// storing them.
	DisableEvents  bool
	EventsInterval time.Duration // default 10 seconds
}

// Provider is an OpenFeature provider that evaluates flags locally.
type Provider struct {
	source      *ConfigSource
	events      chan of.Event
	exposures   *exposureCounter
	experiments *eventSender
}

var (
	_ of.FeatureProvider = (*Provider)(nil)
	_ of.StateHandler    = (*Provider)(nil)
	_ of.EventHandler    = (*Provider)(nil)
	_ of.Tracker         = (*Provider)(nil)
)

// NewProvider checks the options. The config is loaded when OpenFeature initializes the provider.
func NewProvider(o Options) (*Provider, error) {
	src, err := newConfigSource(o)
	if err != nil {
		return nil, err
	}
	p := &Provider{source: src, events: make(chan of.Event, 64)}
	if !o.DisableExposures {
		p.exposures = newExposureCounter(src, o.ExposureInterval)
	}
	if !o.DisableEvents {
		p.experiments = newEventSender(src, o.EventsInterval)
	}
	src.OnChange(p.changed)
	return p, nil
}

// Source gives access to the config source, mostly for tests and diagnostics.
func (p *Provider) Source() *ConfigSource { return p.source }

func (p *Provider) Metadata() of.Metadata { return of.Metadata{Name: "railflag"} }

func (p *Provider) Hooks() []of.Hook { return nil }

func (p *Provider) Init(of.EvaluationContext) error {
	if err := p.source.Start(context.Background()); err != nil {
		return err
	}
	if p.exposures != nil {
		p.exposures.start()
	}
	if p.experiments != nil {
		p.experiments.start()
	}
	return nil
}

// Shutdown stops the config source and sends any evaluation counts and experiment events not sent yet.
func (p *Provider) Shutdown() {
	p.source.Stop()
	if p.exposures != nil {
		p.exposures.shutdown()
	}
	if p.experiments != nil {
		p.experiments.shutdown()
	}
}

// FlushExposures sends the evaluation counts gathered so far. The provider does this every minute and on
// Shutdown; call it yourself only in short-lived programs.
func (p *Provider) FlushExposures() {
	if p.exposures != nil {
		p.exposures.flush()
	}
}

// FlushEvents sends the experiment events queued so far. The provider does this every 10 seconds and on
// Shutdown; call it yourself only in short-lived programs.
func (p *Provider) FlushEvents() {
	if p.experiments != nil {
		p.experiments.flush()
	}
}

// Track records a conversion for your experiments, such as a purchase, with details.Value() as its value
// (an order total, say). It sends nothing unless a running experiment counts the event and the context has a
// targeting key. Use it through the OpenFeature client:
//
//	client.Track(ctx, "purchase", openfeature.NewEvaluationContext("user-42", nil), openfeature.NewTrackingEventDetails(49.9))
func (p *Provider) Track(_ context.Context, event string, c of.EvaluationContext, details of.TrackingEventDetails) {
	if p.experiments == nil {
		return
	}
	x := experimentsOf(p.source.Snapshot()) // nil before the first load, too
	if x == nil {
		return
	}
	if t := trackFor(x, event, toContext(flatten(c)), details.Value()); t != nil {
		p.experiments.event(*t)
	}
}

// flatten is what OpenFeature passes to the evaluation methods: the attributes plus targetingKey.
func flatten(c of.EvaluationContext) of.FlattenedContext {
	out := c.Attributes()
	if k := c.TargetingKey(); k != "" {
		out[of.TargetingKey] = k
	}
	return out
}

func (p *Provider) EventChannel() <-chan of.Event { return p.events }

func (p *Provider) changed(snap, prev Snapshot) {
	if prev == nil {
		return // the first load is the READY event, which OpenFeature sends itself
	}
	before, _ := prev["flags"].(map[string]any)
	after, _ := snap["flags"].(map[string]any)
	var changed []string
	for k, v := range after {
		if !reflect.DeepEqual(before[k], v) {
			changed = append(changed, k)
		}
	}
	for k := range before {
		if _, ok := after[k]; !ok {
			changed = append(changed, k)
		}
	}
	slices.Sort(changed)
	ev := of.Event{ProviderName: "railflag", EventType: of.ProviderConfigChange, ProviderEventDetails: of.ProviderEventDetails{
		Message:     fmt.Sprintf("config version %d", int64(versionOf(snap))),
		FlagChanges: changed,
	}}
	select {
	case p.events <- ev:
	default:
		p.source.log.Warn("railflag event dropped: nobody is reading provider events")
	}
}

var errorCodes = map[string]func(string) of.ResolutionError{
	"FLAG_NOT_FOUND": of.NewFlagNotFoundResolutionError,
	"TYPE_MISMATCH":  of.NewTypeMismatchResolutionError,
	"PARSE_ERROR":    func(m string) of.ResolutionError { return of.NewParseErrorResolutionError(m) },
}

// resolve evaluates a flag. It also returns the experiment exposure the evaluation produces, if any, which the
// typed methods pass to expose once they return the flag's value rather than the default.
func (p *Provider) resolve(flag string, flatCtx of.FlattenedContext, expected string) (any, of.ProviderResolutionDetail, *exposureRecord) {
	snap := p.source.Snapshot()
	if snap == nil {
		return nil, errDetail(of.NewProviderNotReadyResolutionError("Railflag config not loaded yet")), nil
	}
	ctx := toContext(flatCtx)
	r := Evaluate(snap, flag, ctx, expected)
	if r.Reason == "ERROR" {
		mk, ok := errorCodes[r.ErrorCode]
		if !ok {
			mk = func(m string) of.ResolutionError { return of.NewGeneralResolutionError(m) }
		}
		return nil, errDetail(mk(r.ErrorMessage)), nil
	}
	meta := of.FlagMetadata{"configVersion": int64(versionOf(snap))}
	if r.FlagVersion != nil {
		meta["flagVersion"] = int64(*r.FlagVersion)
	} else {
		meta["flagVersion"] = int64(0)
	}
	if r.RuleID != "" {
		meta["ruleId"] = r.RuleID
	}
	if p.exposures != nil && r.VariationIndex != nil {
		p.exposures.record(flag, *r.VariationIndex)
	}
	var x *exposureRecord
	if p.experiments != nil {
		x = exposureFor(experimentsOf(snap), flag, ctx, r)
	}
	return r.Value, of.ProviderResolutionDetail{Reason: of.Reason(r.Reason), Variant: strconv.Itoa(*r.VariationIndex), FlagMetadata: meta}, x
}

// expose queues an experiment exposure (x is nil for most evaluations).
func (p *Provider) expose(x *exposureRecord) {
	if x != nil {
		p.experiments.exposure(*x)
	}
}

func errDetail(e of.ResolutionError) of.ProviderResolutionDetail {
	return of.ProviderResolutionDetail{ResolutionError: e, Reason: of.ErrorReason}
}

func (p *Provider) BooleanEvaluation(_ context.Context, flag string, def bool, c of.FlattenedContext) of.BoolResolutionDetail {
	v, d, x := p.resolve(flag, c, "boolean")
	if d.Error() != nil {
		return of.BoolResolutionDetail{Value: def, ProviderResolutionDetail: d}
	}
	p.expose(x)
	return of.BoolResolutionDetail{Value: v.(bool), ProviderResolutionDetail: d}
}

func (p *Provider) StringEvaluation(_ context.Context, flag string, def string, c of.FlattenedContext) of.StringResolutionDetail {
	v, d, x := p.resolve(flag, c, "string")
	if d.Error() != nil {
		return of.StringResolutionDetail{Value: def, ProviderResolutionDetail: d}
	}
	p.expose(x)
	return of.StringResolutionDetail{Value: v.(string), ProviderResolutionDetail: d}
}

func (p *Provider) FloatEvaluation(_ context.Context, flag string, def float64, c of.FlattenedContext) of.FloatResolutionDetail {
	v, d, x := p.resolve(flag, c, "number")
	if d.Error() != nil {
		return of.FloatResolutionDetail{Value: def, ProviderResolutionDetail: d}
	}
	p.expose(x)
	return of.FloatResolutionDetail{Value: v.(float64), ProviderResolutionDetail: d}
}

func (p *Provider) IntEvaluation(_ context.Context, flag string, def int64, c of.FlattenedContext) of.IntResolutionDetail {
	v, d, x := p.resolve(flag, c, "number")
	if d.Error() != nil {
		return of.IntResolutionDetail{Value: def, ProviderResolutionDetail: d}
	}
	f := v.(float64)
	if f != math.Trunc(f) || math.Abs(f) > 1<<53 {
		return of.IntResolutionDetail{Value: def, ProviderResolutionDetail: errDetail(of.NewTypeMismatchResolutionError(
			fmt.Sprintf("Flag %s is %s, not an integer", flag, jsNumber(f))))}
	}
	p.expose(x)
	return of.IntResolutionDetail{Value: int64(f), ProviderResolutionDetail: d}
}

// ObjectEvaluation returns JSON flags as map[string]any or []any, with numbers as float64.
func (p *Provider) ObjectEvaluation(_ context.Context, flag string, def any, c of.FlattenedContext) of.InterfaceResolutionDetail {
	v, d, x := p.resolve(flag, c, "json")
	if d.Error() != nil {
		return of.InterfaceResolutionDetail{Value: def, ProviderResolutionDetail: d}
	}
	switch v.(type) {
	case map[string]any, []any:
		p.expose(x)
		return of.InterfaceResolutionDetail{Value: v, ProviderResolutionDetail: d}
	}
	return of.InterfaceResolutionDetail{Value: def, ProviderResolutionDetail: errDetail(of.NewTypeMismatchResolutionError(
		fmt.Sprintf("Flag %s value is not an object or list", flag)))}
}

// toContext turns an OpenFeature context into the values the evaluator compares: numbers become
// float64 and times become epoch milliseconds, as in JavaScript.
func toContext(c of.FlattenedContext) map[string]any {
	out := make(map[string]any, len(c))
	for k, v := range c {
		out[k] = normalize(v)
	}
	return out
}

func normalize(v any) any {
	switch t := v.(type) {
	case nil, string, bool, float64:
		return v
	case time.Time:
		return float64(t.UnixMilli())
	case *time.Time:
		if t == nil {
			return nil
		}
		return float64(t.UnixMilli())
	case []any:
		out := make([]any, len(t))
		for i, x := range t {
			out[i] = normalize(x)
		}
		return out
	case map[string]any:
		out := make(map[string]any, len(t))
		for k, x := range t {
			out[k] = normalize(x)
		}
		return out
	}
	rv := reflect.ValueOf(v)
	switch rv.Kind() {
	case reflect.Int, reflect.Int8, reflect.Int16, reflect.Int32, reflect.Int64:
		return float64(rv.Int())
	case reflect.Uint, reflect.Uint8, reflect.Uint16, reflect.Uint32, reflect.Uint64, reflect.Uintptr:
		return float64(rv.Uint())
	case reflect.Float32, reflect.Float64:
		return rv.Float()
	case reflect.String:
		return rv.String()
	case reflect.Bool:
		return rv.Bool()
	case reflect.Pointer, reflect.Interface:
		if rv.IsNil() {
			return nil
		}
		return normalize(rv.Elem().Interface())
	case reflect.Slice, reflect.Array:
		if rv.Kind() == reflect.Slice && rv.IsNil() {
			return nil
		}
		out := make([]any, rv.Len())
		for i := range out {
			out[i] = normalize(rv.Index(i).Interface())
		}
		return out
	case reflect.Map:
		if rv.Type().Key().Kind() != reflect.String {
			return v
		}
		out := make(map[string]any, rv.Len())
		for it := rv.MapRange(); it.Next(); {
			out[it.Key().String()] = normalize(it.Value().Interface())
		}
		return out
	}
	return v
}
