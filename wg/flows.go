package relaywg

import (
	"encoding/json"
	"net"
	"sort"
	"sync"
	"sync/atomic"
	"time"
)

// FlowSnapshot describes one active forwarded flow for live bandwidth monitoring.
type FlowSnapshot struct {
	ID           uint64 `json:"id"`
	Proto        string `json:"proto"`
	Client       string `json:"client"`
	Destination  string `json:"destination"`
	BytesUp      int64  `json:"bytesUp"`
	BytesDown    int64  `json:"bytesDown"`
	StartedUnix  int64  `json:"startedUnix"`
	LastSeenUnix int64  `json:"lastSeenUnix"`
}

type flowEntry struct {
	id          uint64
	proto       string
	client      string
	destination string
	startedUnix int64
	bytesUp     atomic.Int64
	bytesDown   atomic.Int64
	lastSeen    atomic.Int64
}

type flowTracker struct {
	nextID    atomic.Uint64
	mu        sync.Mutex
	active    map[uint64]*flowEntry
	totalUp   atomic.Int64
	totalDown atomic.Int64
}

func newFlowTracker() *flowTracker {
	return &flowTracker{
		active: make(map[uint64]*flowEntry),
	}
}

func (f *flowTracker) start(proto, client, destination string) *flowEntry {
	if f == nil {
		return nil
	}
	now := time.Now().Unix()
	entry := &flowEntry{
		id:          f.nextID.Add(1),
		proto:       proto,
		client:      client,
		destination: destination,
		startedUnix: now,
	}
	entry.lastSeen.Store(now)
	f.mu.Lock()
	f.active[entry.id] = entry
	f.mu.Unlock()
	return entry
}

func (f *flowTracker) finish(entry *flowEntry) {
	if f == nil || entry == nil {
		return
	}
	f.mu.Lock()
	delete(f.active, entry.id)
	f.mu.Unlock()
}

func (f *flowTracker) addUp(entry *flowEntry, n int64) {
	if n <= 0 {
		return
	}
	if f != nil {
		f.totalUp.Add(n)
	}
	if entry != nil {
		entry.bytesUp.Add(n)
		entry.lastSeen.Store(time.Now().Unix())
	}
}

func (f *flowTracker) addDown(entry *flowEntry, n int64) {
	if n <= 0 {
		return
	}
	if f != nil {
		f.totalDown.Add(n)
	}
	if entry != nil {
		entry.bytesDown.Add(n)
		entry.lastSeen.Store(time.Now().Unix())
	}
}

func (f *flowTracker) snapshot() []FlowSnapshot {
	if f == nil {
		return nil
	}
	f.mu.Lock()
	out := make([]FlowSnapshot, 0, len(f.active))
	for _, entry := range f.active {
		out = append(out, FlowSnapshot{
			ID:           entry.id,
			Proto:        entry.proto,
			Client:       entry.client,
			Destination:  entry.destination,
			BytesUp:      entry.bytesUp.Load(),
			BytesDown:    entry.bytesDown.Load(),
			StartedUnix:  entry.startedUnix,
			LastSeenUnix: entry.lastSeen.Load(),
		})
	}
	f.mu.Unlock()

	sort.Slice(out, func(i, j int) bool {
		ti := out[i].BytesUp + out[i].BytesDown
		tj := out[j].BytesUp + out[j].BytesDown
		if ti != tj {
			return ti > tj
		}
		return out[i].LastSeenUnix > out[j].LastSeenUnix
	})
	if len(out) > 100 {
		out = out[:100]
	}
	return out
}

func (f *flowTracker) snapshotJSON() string {
	snaps := f.snapshot()
	if snaps == nil {
		snaps = []FlowSnapshot{}
	}
	raw, err := json.Marshal(snaps)
	if err != nil {
		return "[]"
	}
	return string(raw)
}

func (f *flowTracker) totals() (up, down int64) {
	if f == nil {
		return 0, 0
	}
	return f.totalUp.Load(), f.totalDown.Load()
}

func connClientLabel(c net.Conn) string {
	if c == nil || c.RemoteAddr() == nil {
		return ""
	}
	host, _, err := net.SplitHostPort(c.RemoteAddr().String())
	if err == nil {
		return host
	}
	return c.RemoteAddr().String()
}
