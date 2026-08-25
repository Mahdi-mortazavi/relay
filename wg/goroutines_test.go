package relaywg

import (
	"fmt"
	"io"
	"net/http"
	"runtime"
	"testing"
	"time"
)

// Connections must give their goroutines back.
//
// Nothing pinned this, and the endpoint is exactly the shape where it goes
// wrong: every forwarded connection runs two copy goroutines and holds a
// socket and a 64 KB buffer, and UDP -- which never says when it is finished --
// gets one flow per DNS lookup. A leak here does not show up as a crash. It
// shows up as a phone that is fine for ten minutes and then is not, which is
// the hardest kind of report to act on.
//
// Counted rather than reasoned about, and settled with a deadline rather than a
// sleep: goroutines exit asynchronously, so a fixed sleep either makes this
// flaky or makes it slow.
func TestForwardedConnectionsDoNotLeakGoroutines(t *testing.T) {
	const (
		connections = 40
		// Enough slack for the runtime's own workers and the tunnel's internal
		// goroutines to move around, far less than 2 per connection.
		slack = 12
	)

	destination := httpServer(t, "a body worth fetching")

	serverPrivate, serverPublic := keyPair(t)
	clientPrivate, clientPublic := keyPair(t)
	port := freeUDPPort(t)

	endpoint, err := Start(fmt.Sprintf(`private_key=%s
listen_port=%d
public_key=%s
allowed_ip=10.13.37.2/32
`, serverPrivate, port, clientPublic))
	if err != nil {
		t.Fatalf("endpoint did not start: %v", err)
	}
	defer endpoint.Stop()

	client, clientNet := wireguardClient(t, clientPrivate, serverPublic, port)
	defer client.Close()

	transport := &http.Transport{
		DialContext: clientNet.DialContext,
		// Every request gets its own connection, which is the point: pooled
		// connections would hide exactly the leak being looked for.
		DisableKeepAlives: true,
	}
	httpClient := &http.Client{Transport: transport, Timeout: 20 * time.Second}

	fetch := func() error {
		response, err := httpClient.Get("http://" + destination + "/")
		if err != nil {
			return err
		}
		_, _ = io.Copy(io.Discard, response.Body)
		return response.Body.Close()
	}

	// Warm up until the handshake completes, so the baseline is taken with the
	// tunnel already running rather than mid-setup.
	deadline := time.Now().Add(20 * time.Second)
	for time.Now().Before(deadline) {
		if fetch() == nil {
			break
		}
		time.Sleep(250 * time.Millisecond)
	}
	transport.CloseIdleConnections()
	baseline := settledGoroutines(t, 0)

	for i := 0; i < connections; i++ {
		if err := fetch(); err != nil {
			t.Fatalf("request %d through the tunnel failed: %v", i, err)
		}
	}
	transport.CloseIdleConnections()

	after := settledGoroutines(t, baseline+slack)
	if after > baseline+slack {
		buffer := make([]byte, 1<<16)
		buffer = buffer[:runtime.Stack(buffer, true)]
		t.Fatalf("goroutines went %d -> %d over %d connections (allowing %d).\n"+
			"Two per forwarded connection are never returned when this breaks.\n\n%s",
			baseline, after, connections, slack, buffer)
	}
	t.Logf("goroutines %d -> %d over %d connections", baseline, after, connections)
}

// settledGoroutines waits for the count to stop moving, or to fall within
// target, whichever happens first.
func settledGoroutines(t *testing.T, target int) int {
	t.Helper()

	deadline := time.Now().Add(15 * time.Second)
	last := runtime.NumGoroutine()
	stable := 0
	for time.Now().Before(deadline) {
		time.Sleep(200 * time.Millisecond)
		now := runtime.NumGoroutine()
		if target > 0 && now <= target {
			return now
		}
		if now == last {
			if stable++; stable >= 3 {
				return now
			}
		} else {
			stable = 0
			last = now
		}
	}
	return runtime.NumGoroutine()
}
