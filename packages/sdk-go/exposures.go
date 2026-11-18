package railflag

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"sync"
	"time"
)

// maxExposureFlags caps the distinct flags counted between two sends (protects memory if keys are generated).
const maxExposureFlags = 2000

// exposureCounter counts flag evaluations per flag and variation and sends the totals to the relay once a
// minute, so Release Watch can see how many users got each variation. Only counts leave the process: no
// contexts, no user ids. Sending never delays a flag check, and a failed send is dropped.
type exposureCounter struct {
	src      *ConfigSource
	interval time.Duration

	mu     sync.Mutex
	counts map[string]map[int]int

	stop chan struct{}
	done chan struct{}
}

func newExposureCounter(src *ConfigSource, interval time.Duration) *exposureCounter {
	if interval <= 0 {
		interval = time.Minute
	}
	return &exposureCounter{src: src, interval: interval, counts: map[string]map[int]int{}}
}

func (e *exposureCounter) record(flag string, variation int) {
	e.mu.Lock()
	defer e.mu.Unlock()
	byVar, ok := e.counts[flag]
	if !ok {
		if len(e.counts) >= maxExposureFlags {
			return
		}
		byVar = map[int]int{}
		e.counts[flag] = byVar
	}
	byVar[variation]++
}

func (e *exposureCounter) start() {
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
			}
		}
	}()
}

// flush sends what was counted since the last flush. Nothing is sent when nothing was evaluated.
func (e *exposureCounter) flush() {
	e.mu.Lock()
	counts := e.counts
	e.counts = map[string]map[int]int{}
	e.mu.Unlock()
	if len(counts) == 0 {
		return
	}
	out := make(map[string]map[string]int, len(counts))
	for flag, byVar := range counts {
		m := make(map[string]int, len(byVar))
		for v, n := range byVar {
			m[strconv.Itoa(v)] = n
		}
		out[flag] = m
	}
	body, err := json.Marshal(map[string]any{"counts": out})
	if err != nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, e.src.baseURL+"/sdk/v1/exposures", bytes.NewReader(body))
	if err != nil {
		return
	}
	req.Header.Set("Authorization", e.src.sdkKey)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", "railflag-go/"+Version)
	res, err := e.src.client.Do(req)
	if err != nil {
		e.src.log.Debug("railflag exposure counts not sent", "err", err)
		return
	}
	res.Body.Close()
}

func (e *exposureCounter) shutdown() {
	if e.stop != nil {
		close(e.stop)
		<-e.done
		e.stop = nil
	}
	e.flush()
}
