package relaywg

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"fmt"
	"io"
	"net"
	"testing"
	"time"

	"golang.zx2c4.com/wireguard/tun/netstack"
)

// The shapes real traffic actually takes, through a real tunnel.
//
// The suite already proved that one HTTP request and one UDP datagram survive
// the trip. That is the "does it work at all" question, and it is not the one
// users report against: what breaks is a download that is still going after
// five minutes, an SSH session that says nothing for a while, a phone whose
// endpoint restarted, a transfer that arrives subtly wrong. Every one of those
// is a shape, and none of them was covered.
//
// These run against the same in-process pairing the other end-to-end tests use:
// a real wireguard-go client, real encryption, a real gVisor stack, and real
// sockets on the far side. What they cannot cover is anything about a radio or
// a suspended machine -- Wi-Fi changes and sleep/wake need hardware, and are
// named as such in the report rather than faked here.

// tunnelPair brings up an endpoint and a client and returns the client's stack.
func tunnelPair(t *testing.T) (*netstack.Net, func()) {
	t.Helper()

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

	client, clientNet := wireguardClient(t, clientPrivate, serverPublic, port)
	return clientNet, func() {
		client.Close()
		endpoint.Stop()
	}
}

// dialThroughTunnel retries until the handshake has completed.
func dialThroughTunnel(t *testing.T, clientNet *netstack.Net, address string) net.Conn {
	t.Helper()

	deadline := time.Now().Add(25 * time.Second)
	for time.Now().Before(deadline) {
		conn, err := clientNet.Dial("tcp", address)
		if err == nil {
			return conn
		}
		time.Sleep(200 * time.Millisecond)
	}
	t.Fatalf("nothing could be dialled through the tunnel to %s", address)
	return nil
}

// echoServer accepts one connection and copies it back.
func echoServer(t *testing.T) string {
	t.Helper()

	listener, err := net.Listen("tcp", nonLoopbackAddress(t)+":0")
	if err != nil {
		t.Fatalf("echo server: %v", err)
	}
	t.Cleanup(func() { listener.Close() })

	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go func() {
				defer conn.Close()
				_, _ = io.Copy(conn, conn)
			}()
		}
	}()
	return listener.Addr().String()
}

// A connection that is still working must not be reaped, through the whole
// tunnel rather than across a pipe.
//
// forward_test.go pins the same rule directly on the splice. This pins it where
// it is actually reached: through the gVisor stack, through the forwarder, on a
// connection the client opened. A download crossing the five-minute mark is the
// case users reported, and the idle timeout is shortened here rather than
// waiting five minutes for it.
func TestALongRunningConnectionSurvivesTheIdleTimeout(t *testing.T) {
	restore := shortenIdleTimeouts(t, 400*time.Millisecond)
	defer restore()

	destination := echoServer(t)
	clientNet, stop := tunnelPair(t)
	defer stop()

	conn := dialThroughTunnel(t, clientNet, destination)
	defer conn.Close()

	// Quiet stretches longer than half the timeout, repeated well past it.
	message := []byte("still here")
	reply := make([]byte, len(message))
	for i := 0; i < 8; i++ {
		_ = conn.SetDeadline(time.Now().Add(10 * time.Second))
		if _, err := conn.Write(message); err != nil {
			t.Fatalf("write %d died after %v of a working connection: %v",
				i, time.Duration(i)*300*time.Millisecond, err)
		}
		if _, err := io.ReadFull(conn, reply); err != nil {
			t.Fatalf("read %d died on a working connection: %v", i, err)
		}
		if !bytes.Equal(reply, message) {
			t.Fatalf("echo %d came back as %q", i, reply)
		}
		time.Sleep(300 * time.Millisecond)
	}
}

// Bulk transfer, both directions, checked for integrity rather than for volume.
//
// Throughput is measured by the benchmarks. What this asks is whether several
// megabytes arrive *exactly* -- a packet path that drops, duplicates or
// reorders under load is a corruption bug, and a size check would not see it.
func TestBulkTransferArrivesIntactInBothDirections(t *testing.T) {
	const size = 4 << 20

	payload := make([]byte, size)
	if _, err := rand.Read(payload); err != nil {
		t.Fatalf("payload: %v", err)
	}
	want := sha256.Sum256(payload)

	destination := echoServer(t)
	clientNet, stop := tunnelPair(t)
	defer stop()

	conn := dialThroughTunnel(t, clientNet, destination)
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(90 * time.Second))

	// Upload and download at once, which is also the case that deadlocks a
	// splice that waits on the wrong direction.
	uploaded := make(chan error, 1)
	go func() {
		_, err := conn.Write(payload)
		uploaded <- err
	}()

	returned := make([]byte, size)
	if _, err := io.ReadFull(conn, returned); err != nil {
		t.Fatalf("only part of the data came back: %v", err)
	}
	if err := <-uploaded; err != nil {
		t.Fatalf("upload failed: %v", err)
	}

	if got := sha256.Sum256(returned); got != want {
		t.Fatalf("what came back is not what went out: %x != %x", got, want)
	}
}

// The SSH and websocket shape: open, then long silences with small messages in
// both directions, for much longer than the idle timeout.
//
// This is the shape the five-minute bug destroyed most visibly, and it is not
// the same as the download shape: traffic goes both ways, but rarely.
func TestAnInteractiveSessionSurvivesLongSilences(t *testing.T) {
	restore := shortenIdleTimeouts(t, 400*time.Millisecond)
	defer restore()

	destination := echoServer(t)
	clientNet, stop := tunnelPair(t)
	defer stop()

	conn := dialThroughTunnel(t, clientNet, destination)
	defer conn.Close()

	for i := 0; i < 6; i++ {
		// Silence longer than half the idle timeout, every time.
		time.Sleep(250 * time.Millisecond)

		keystroke := []byte(fmt.Sprintf("keystroke-%d\n", i))
		_ = conn.SetDeadline(time.Now().Add(10 * time.Second))
		if _, err := conn.Write(keystroke); err != nil {
			t.Fatalf("the session died on keystroke %d: %v", i, err)
		}
		echo := make([]byte, len(keystroke))
		if _, err := io.ReadFull(conn, echo); err != nil {
			t.Fatalf("no echo for keystroke %d: %v", i, err)
		}
		if !bytes.Equal(echo, keystroke) {
			t.Fatalf("keystroke %d came back as %q", i, echo)
		}
	}
}

// UDP has to survive silence too, and it is the one that cannot say so.
//
// A UDP flow is reaped by the idle timeout alone, so a flow that keeps being
// used must keep being renewed. DNS is the common case; a game or a QUIC
// session is the one that would notice.
func TestAUdpFlowSurvivesBeingUsedSlowly(t *testing.T) {
	restore := shortenIdleTimeouts(t, 400*time.Millisecond)
	defer restore()

	destination := udpEchoServer(t)
	clientNet, stop := tunnelPair(t)
	defer stop()

	var conn net.Conn
	deadline := time.Now().Add(25 * time.Second)
	for time.Now().Before(deadline) {
		candidate, err := clientNet.Dial("udp", destination)
		if err == nil {
			conn = candidate
			break
		}
		time.Sleep(200 * time.Millisecond)
	}
	if conn == nil {
		t.Fatal("no UDP flow could be opened through the tunnel")
	}
	defer conn.Close()

	reply := make([]byte, 64)
	for i := 0; i < 5; i++ {
		message := []byte(fmt.Sprintf("datagram-%d", i))
		_ = conn.SetDeadline(time.Now().Add(10 * time.Second))
		if _, err := conn.Write(message); err != nil {
			t.Fatalf("datagram %d could not be sent: %v", i, err)
		}
		n, err := conn.Read(reply)
		if err != nil {
			t.Fatalf("datagram %d was never answered: %v", i, err)
		}
		if !bytes.Equal(reply[:n], message) {
			t.Fatalf("datagram %d came back as %q", i, reply[:n])
		}
		time.Sleep(250 * time.Millisecond)
	}
}

// The phone's endpoint going away and coming back.
//
// This is the ordinary case, not an exotic one: sharing stops and starts, the
// app is killed, the service is restarted by the system. What must not happen
// is the old endpoint leaving something behind that stops the new one working
// -- a held port, a live forwarder, a stack that was never closed.
func TestTrafficFlowsAgainAfterTheEndpointRestarts(t *testing.T) {
	destination := echoServer(t)

	serverPrivate, serverPublic := keyPair(t)
	clientPrivate, clientPublic := keyPair(t)
	port := freeUDPPort(t)

	config := fmt.Sprintf(`private_key=%s
listen_port=%d
public_key=%s
allowed_ip=10.13.37.2/32
`, serverPrivate, port, clientPublic)

	endpoint, err := Start(config)
	if err != nil {
		t.Fatalf("endpoint did not start: %v", err)
	}
	client, clientNet := wireguardClient(t, clientPrivate, serverPublic, port)
	defer client.Close()

	exchange := func(stage string) {
		t.Helper()
		conn := dialThroughTunnel(t, clientNet, destination)
		defer conn.Close()
		_ = conn.SetDeadline(time.Now().Add(20 * time.Second))
		message := []byte("hello " + stage)
		if _, err := conn.Write(message); err != nil {
			t.Fatalf("%s: write: %v", stage, err)
		}
		echo := make([]byte, len(message))
		if _, err := io.ReadFull(conn, echo); err != nil {
			t.Fatalf("%s: read: %v", stage, err)
		}
	}

	exchange("before the restart")

	endpoint.Stop()

	// Same port and same keys, which is what a phone that restarted sharing
	// without re-pairing would present.
	restarted, err := Start(config)
	if err != nil {
		t.Fatalf("the endpoint did not come back on the same port: %v", err)
	}
	defer restarted.Stop()

	exchange("after the restart")
}

// shortenIdleTimeouts makes the reaping observable in a test rather than in
// five minutes, and puts the real values back afterwards.
//
// Not parallel-safe, and deliberately so: these tests do not call t.Parallel.
func shortenIdleTimeouts(t *testing.T, idle time.Duration) func() {
	t.Helper()
	previousTCP, previousUDP := tcpIdleTimeout, udpIdleTimeout
	tcpIdleTimeout, udpIdleTimeout = idle, idle
	return func() { tcpIdleTimeout, udpIdleTimeout = previousTCP, previousUDP }
}
