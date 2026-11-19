package railflag

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"sync"
	"time"
)

const (
	// maxEventBatch is the most exposures, and the most events, in one request: the relay accepts 1,000 of each.
	maxEventBatch = 1000
	// eventSendAt sends early once this many items wait, so a busy process doesn't hold big queues all interval.
	eventSendAt = 500
	// maxEventQueue caps each queue while the relay can't be reached; newer items are dropped.
	maxEventQueue = 10_000
)

type timedExposure struct {
	exposureRecord
	TS int64 `json:"ts"`
}

type timedEvent struct {
	trackRecord
	TS int64 `json:"ts"`
}

type eventsBody struct {
	SentAt    int64           `json:"sentAt"`
	SDK       string          `json:"sdk"`
	Exposures []timedExposure `json:"exposures"`
	Events    []timedEvent    `json:"events"`
}

// eventSender sends experiment data to the relay: one exposure per user and variation of a flag in a running
// experiment, and the Track calls an experiment counts. User keys leave the process here, only for those flags
// and events; Railflag hashes them before storing them. Queuing never delays a flag check, sending runs on a
// background goroutine, and a failed send is dropped rather than retried.
type eventSender struct {
	src      *ConfigSource
	interval time.Duration
	dedupe   *exposureDedupe

	mu         sync.Mutex
	exposures  []timedExposure
	events     []timedEvent
	warnedFull bool
	lastWarn   time.Time

	sendMu sync.Mutex    // one flush at a time, so batches arrive in order
	kick   chan struct{} // asks the background goroutine to send early
	stop   chan struct{}
	done   chan struct{}
}

func newEventSender(src *ConfigSource, interval time.Duration) *eventSender {
	if interval <= 0 {
		interval = 10 * time.Second
	}
	return &eventSender{src: src, interval: interval, dedupe: newExposureDedupe(maxDedupe), kick: make(chan struct{}, 1)}
}

// exposure queues r, unless the same user got the same variation of the flag before.
func (e *eventSender) exposure(r exposureRecord) {
	if e.dedupe.first(r) {
		enqueue(e, &e.exposures, timedExposure{r, time.Now().UnixMilli()})
	}
}

// event queues r. Events are never de-duplicated: each Track call counts.
func (e *eventSender) event(r trackRecord) {
	enqueue(e, &e.events, timedEvent{r, time.Now().UnixMilli()})
}

func enqueue[T any](e *eventSender, q *[]T, item T) {
	e.mu.Lock()
	if len(*q) >= maxEventQueue {
		warn := !e.warnedFull
		e.warnedFull = true
		e.mu.Unlock()
		if warn {
			e.src.log.Warn("railflag experiment events: queue full, dropping new ones until the relay can be reached")
		}
		return
	}
	*q = append(*q, item)
	early := len(e.exposures)+len(e.events) >= eventSendAt
	e.mu.Unlock()
	if early {
		select {
		case e.kick <- struct{}{}:
		default: // a send is already due
		}
	}
}

func (e *eventSender) start() {
	e.stop, e.done = make(chan struct{}), make(chan struct{})
	go func() {
		defer close(e.done)
		t := time.NewTicker(e.interval)
		defer t.Stop()
		for {
			select {
			case <-e.stop:
				return
			case <-t.C:
				e.flush()
			case <-e.kick:
				e.flush()
			}
		}
	}()
}

// flush sends what is queued, in requests of at most maxEventBatch exposures and maxEventBatch events.
// Nothing is sent when nothing is queued.
func (e *eventSender) flush() {
	e.sendMu.Lock()
	defer e.sendMu.Unlock()
	e.mu.Lock()
	exposures, events := e.exposures, e.events
	e.exposures, e.events = nil, nil
	e.mu.Unlock()
	for len(exposures) > 0 || len(events) > 0 {
		nx, ne := min(len(exposures), maxEventBatch), min(len(events), maxEventBatch)
		e.send(exposures[:nx], events[:ne])
		exposures, events = exposures[nx:], events[ne:]
	}
}

func (e *eventSender) send(exposures []timedExposure, events []timedEvent) {
	if exposures == nil {
		exposures = []timedExposure{} // [] rather than null
	}
	if events == nil {
		events = []timedEvent{}
	}
	body, err := json.Marshal(eventsBody{SentAt: time.Now().UnixMilli(), SDK: "go/" + Version, Exposures: exposures, Events: events})
	if err != nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, e.src.baseURL+"/sdk/v1/events", bytes.NewReader(body))
	if err != nil {
		return
	}
	req.Header.Set("Authorization", e.src.sdkKey)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", "railflag-go/"+Version)
	res, err := e.src.client.Do(req)
	if err != nil {
		e.warn("railflag experiment events not sent", "error", err)
		return
	}
	res.Body.Close()
	switch {
	case res.StatusCode >= 200 && res.StatusCode < 300:
		e.mu.Lock()
		e.warnedFull = false
		e.mu.Unlock()
	case res.StatusCode != http.StatusNotFound: // 404: a relay from before experiments, nothing to report
		e.warn("railflag experiment events not accepted", "status", res.StatusCode)
	}
}

// warn logs at most once a minute, so an unreachable relay doesn't flood the logs.
func (e *eventSender) warn(msg string, args ...any) {
	e.mu.Lock()
	now := time.Now()
	quiet := !e.lastWarn.IsZero() && now.Sub(e.lastWarn) < time.Minute
	if !quiet {
		e.lastWarn = now
	}
	e.mu.Unlock()
	if !quiet {
		e.src.log.Warn(msg, args...)
	}
}

func (e *eventSender) shutdown() {
	if e.stop != nil {
		close(e.stop)
		<-e.done
		e.stop = nil
	}
	e.flush()
}
