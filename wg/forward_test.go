package relaywg

import (
	"bytes"
	"net"
	"testing"
	"time"
)

// A download must not be torn down while it is downloading.
//
// This is the test that was missing, and it has now caught two separate bugs.
// The original forward set a deadline once, five minutes out, and called the
// variable holding it an idle timeout -- so a connection carrying traffic the
// whole time died at exactly five minutes, and a download, a call or an SSH
// session died with it.
//
// The first fix then failed this too, for a better reason: it gave each
// direction its own idle timeout. A download is silent upstream from beginning
// to end, so that reaped every download at the timeout -- sooner than the bug
// it replaced. The traffic below only ever flows one way, deliberately, because
// that is the shape that breaks.
//
// Written against a short idle so it takes milliseconds rather than minutes:
// what is under test is whether the deadline moves with the traffic, and that
// is the same question at any scale.
func TestADownloadOutlivesTheIdleTimeout(t *testing.T) {
	t.Parallel()

	const idle = 100 * time.Millisecond
	// Long enough that a deadline armed once at the start has expired several
	// times over by the end.
	const runFor = 600 * time.Millisecond

	clientSide, tunnelSide := net.Pipe()
	remoteSide, originSide := net.Pipe()
	go forward(tunnelSide, remoteSide, idle)

	chunk := []byte("still here")
	deadline := time.Now().Add(runFor)
	sent := 0

	go func() {
		for time.Now().Before(deadline) {
			if _, err := originSide.Write(chunk); err != nil {
				return
			}
			time.Sleep(idle / 4)
		}
		originSide.Close()
	}()

	got := make([]byte, len(chunk))
	for time.Now().Before(deadline) {
		_ = clientSide.SetReadDeadline(time.Now().Add(2 * time.Second))
		n, err := clientSide.Read(got)
		if err != nil {
			t.Fatalf("the splice died after %d chunks with %v; a busy connection "+
				"must not be reaped by an idle timeout", sent, err)
		}
		if !bytes.Equal(got[:n], chunk[:n]) {
			t.Fatalf("corrupted chunk %d: %q", sent, got[:n])
		}
		sent++
	}
	if sent == 0 {
		t.Fatal("nothing crossed the splice at all")
	}
}

// And a connection that is genuinely idle must be reaped, or UDP would hold a
// socket and two goroutines per DNS lookup for the life of the session.
func TestAnIdleConnectionIsReaped(t *testing.T) {
	t.Parallel()

	const idle = 100 * time.Millisecond

	clientSide, tunnelSide := net.Pipe()
	remoteSide, _ := net.Pipe()
	go forward(tunnelSide, remoteSide, idle)

	// Nothing is ever written, so the deadline is never pushed forward and the
	// splice must give up and close this end.
	_ = clientSide.SetReadDeadline(time.Now().Add(5 * time.Second))
	if _, err := clientSide.Read(make([]byte, 1)); err == nil {
		t.Fatal("an idle splice should have closed, but the read succeeded")
	}
}
