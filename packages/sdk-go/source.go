package railflag

// ConfigSource keeps the latest environment snapshot in memory, like packages/sdk-node/src/source.ts.
//
// Flag checks never wait on the network. Updates arrive over Server-Sent Events; polling every
// PollInterval is the safety net, and if Railflag can't be reached the last config keeps serving.

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// Version is this SDK's version, sent in the User-Agent header.
const Version = "0.2.0"

// ChangeListener runs on a background goroutine after each update. prev is nil on the first load.
type ChangeListener func(snap, prev Snapshot)

type ConfigSource struct {
	sdkKey       string
	baseURL      string
	stream       bool
	pollInterval time.Duration
	timeout      time.Duration
	// The relay sends a heartbeat every 25 s, so a silent stream for this long is a dead one.
	streamReadTimeout time.Duration
	client            *http.Client
	log               *slog.Logger

	snapshot  atomic.Pointer[Snapshot]
	streaming atomic.Bool

	fetchMu   sync.Mutex
	etag      string
	listeners []ChangeListener

	cancel context.CancelFunc
	wg     sync.WaitGroup
}

func newConfigSource(o Options) (*ConfigSource, error) {
	if !strings.HasPrefix(o.BaseURL, "http://") && !strings.HasPrefix(o.BaseURL, "https://") {
		return nil, fmt.Errorf("railflag: BaseURL must be your Railflag relay URL, like https://relay.example.com (got %q)", o.BaseURL)
	}
	if o.SDKKey == "" {
		return nil, errors.New("railflag: SDKKey is required (a server key from the Environments page)")
	}
	s := &ConfigSource{
		sdkKey:            o.SDKKey,
		baseURL:           strings.TrimRight(o.BaseURL, "/"),
		stream:            !o.DisableStream,
		pollInterval:      o.PollInterval,
		timeout:           o.Timeout,
		streamReadTimeout: 60 * time.Second,
		client:            o.HTTPClient,
		log:               o.Logger,
	}
	if s.pollInterval <= 0 {
		s.pollInterval = 30 * time.Second
	}
	if s.timeout <= 0 {
		s.timeout = 10 * time.Second
	}
	if s.client == nil {
		s.client = &http.Client{}
	}
	if s.log == nil {
		s.log = slog.Default().With("logger", "railflag")
	}
	return s, nil
}

// Snapshot returns the current config, or nil before the first load.
func (s *ConfigSource) Snapshot() Snapshot {
	if p := s.snapshot.Load(); p != nil {
		return *p
	}
	return nil
}

// Streaming reports whether the SSE stream is connected.
func (s *ConfigSource) Streaming() bool { return s.streaming.Load() }

// OnChange registers a listener. Register listeners before Start.
func (s *ConfigSource) OnChange(l ChangeListener) { s.listeners = append(s.listeners, l) }

// Start loads the config once (returning the error if that fails), then keeps it fresh in the background.
func (s *ConfigSource) Start(ctx context.Context) error {
	if _, err := s.Fetch(ctx); err != nil {
		return err
	}
	bg, cancel := context.WithCancel(context.Background())
	s.cancel = cancel
	s.wg.Add(1)
	go s.pollLoop(bg)
	if s.stream {
		s.wg.Add(1)
		go s.streamLoop(bg)
	}
	return nil
}

// Stop ends the background goroutines and waits for them.
func (s *ConfigSource) Stop() {
	if s.cancel != nil {
		s.cancel()
	}
	s.wg.Wait()
}

func (s *ConfigSource) newRequest(ctx context.Context, path string) (*http.Request, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, s.baseURL+path, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", s.sdkKey)
	req.Header.Set("User-Agent", "railflag-go/"+Version)
	return req, nil
}

// Fetch downloads the snapshot if it changed. It reports whether a newer one was applied.
func (s *ConfigSource) Fetch(ctx context.Context) (bool, error) {
	s.fetchMu.Lock()
	ctx, cancel := context.WithTimeout(ctx, s.timeout)
	defer cancel()
	req, err := s.newRequest(ctx, "/sdk/v1/config")
	if err != nil {
		s.fetchMu.Unlock()
		return false, err
	}
	if s.etag != "" {
		req.Header.Set("If-None-Match", s.etag)
	}
	resp, err := s.client.Do(req)
	if err != nil {
		s.fetchMu.Unlock()
		return false, fmt.Errorf("railflag: config request failed: %w", err)
	}
	body, err := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode == http.StatusNotModified {
		s.fetchMu.Unlock()
		return false, nil
	}
	if err == nil && resp.StatusCode != http.StatusOK {
		err = fmt.Errorf("railflag: config request failed: %d %.200s", resp.StatusCode, body)
	}
	if err != nil {
		s.fetchMu.Unlock()
		return false, err
	}
	parsed, err := parseJSON(body)
	snap, _ := parsed.(map[string]any)
	if err != nil || snap == nil {
		s.fetchMu.Unlock()
		return false, fmt.Errorf("railflag: config is not a JSON object: %v", err)
	}
	prev := s.Snapshot()
	if prev != nil && versionOf(snap) < versionOf(prev) {
		s.fetchMu.Unlock()
		return false, nil
	}
	s.snapshot.Store(&snap)
	s.etag = resp.Header.Get("ETag")
	s.fetchMu.Unlock()

	for _, l := range s.listeners {
		func() {
			defer func() {
				if p := recover(); p != nil {
					s.log.Error("railflag change listener failed", "panic", p)
				}
			}()
			l(snap, prev)
		}()
	}
	return true, nil
}

func versionOf(snap Snapshot) float64 {
	v, _ := snap["version"].(float64)
	return v
}

func (s *ConfigSource) pollLoop(ctx context.Context) {
	defer s.wg.Done()
	t := time.NewTicker(s.pollInterval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			if _, err := s.Fetch(ctx); err != nil && ctx.Err() == nil {
				s.log.Warn("railflag poll failed", "error", err)
			}
		}
	}
}

func (s *ConfigSource) streamLoop(ctx context.Context) {
	defer s.wg.Done()
	backoff := 500 * time.Millisecond
	for ctx.Err() == nil {
		connected, err := s.readStream(ctx)
		s.streaming.Store(false)
		if ctx.Err() != nil {
			return
		}
		if connected {
			backoff = 500 * time.Millisecond
		}
		if err != nil {
			s.log.Warn("railflag stream dropped, reconnecting", "error", err)
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}
		backoff = min(backoff*2, 30*time.Second)
	}
}

// readStream reads one SSE connection until it ends. connected reports whether it got a 200.
func (s *ConfigSource) readStream(parent context.Context) (connected bool, err error) {
	ctx, cancel := context.WithCancel(parent)
	defer cancel()
	req, err := s.newRequest(ctx, "/sdk/v1/stream")
	if err != nil {
		return false, err
	}
	req.Header.Set("Accept", "text/event-stream")
	resp, err := s.client.Do(req)
	if err != nil {
		return false, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return false, fmt.Errorf("stream request failed: %d", resp.StatusCode)
	}
	s.streaming.Store(true)

	// No line for streamReadTimeout means the connection is dead: cancel the request.
	watchdog := time.AfterFunc(s.streamReadTimeout, cancel)
	defer watchdog.Stop()

	r := bufio.NewReader(resp.Body)
	var event, data string
	hasData := false
	for {
		line, err := r.ReadString('\n')
		if err != nil {
			if parent.Err() != nil {
				return true, nil
			}
			if errors.Is(err, io.EOF) {
				return true, errors.New("server closed the stream")
			}
			return true, err
		}
		watchdog.Reset(s.streamReadTimeout)
		line = strings.TrimRight(line, "\r\n")
		if line == "" {
			switch event {
			case "reconnect":
				return true, nil
			case "config":
				s.onConfigEvent(ctx, data)
			}
			event, data, hasData = "", "", false
			continue
		}
		if strings.HasPrefix(line, ":") {
			continue
		}
		field, value, _ := strings.Cut(line, ":")
		value = strings.TrimPrefix(value, " ")
		switch field {
		case "event":
			event = value
		case "data":
			if hasData {
				data += "\n" + value
			} else {
				data, hasData = value, true
			}
		}
	}
}

func (s *ConfigSource) onConfigEvent(ctx context.Context, data string) {
	parsed, err := parseJSON([]byte(data))
	if err != nil {
		return
	}
	m, _ := parsed.(map[string]any)
	v, ok := m["version"].(float64)
	if !ok {
		return
	}
	if cur := s.Snapshot(); cur == nil || v > versionOf(cur) {
		if _, err := s.Fetch(ctx); err != nil && ctx.Err() == nil {
			s.log.Warn("railflag fetch after stream event failed", "error", err)
		}
	}
}
