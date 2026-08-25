package relaywg

import (
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"testing"
	"time"

	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
)

// The two hot paths, measured rather than argued about.
//
// Everything this endpoint does for a byte of a user's traffic happens in one
// of two places: a packet crossing the tun boundary, and a payload crossing the
// splice between the tunnel and a real socket. Both run on a phone, on the one
// CPU that measurement has repeatedly shown to be the limit, so allocation
// counts here are not a style question -- they are garbage the collector has to
// chase while the transfer is running.
//
// -benchmem is the point. ns/op on a shared CI runner is noisy; allocs/op is
// deterministic and is what actually regressed unnoticed before.

// packet builds one MTU-sized frame, the size the tunnel actually carries.
func packet(size int) *stack.PacketBuffer {
	return stack.NewPacketBuffer(stack.PacketBufferOptions{
		Payload: buffer.MakeWithData(make([]byte, size)),
	})
}

// BenchmarkTunRead measures one packet's trip out of the stack and into the
// buffer wireguard-go will encrypt from -- the read half of every byte the
// laptop downloads.
func BenchmarkTunRead(b *testing.B) {
	device, err := newNetTun(mtu)
	if err != nil {
		b.Fatal(err)
	}
	defer device.Close()

	bufs := [][]byte{make([]byte, mtu+128)}
	sizes := make([]int, 1)

	b.ReportAllocs()
	b.SetBytes(int64(mtu))
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		var list stack.PacketBufferList
		list.PushBack(packet(mtu))
		if _, err := device.endpoint.WritePackets(list); err != nil {
			b.Fatalf("queueing: %v", err)
		}
		list.Reset()
		if _, err := device.Read(bufs, sizes, 0); err != nil {
			b.Fatalf("reading: %v", err)
		}
	}
}

// BenchmarkTunReadBatch measures the same path when packets are already queued,
// which is what happens under load: the batch is the unit wireguard-go hands to
// a crypto worker, and a batch of one pays the whole per-batch cost per packet.
func BenchmarkTunReadBatch(b *testing.B) {
	device, err := newNetTun(mtu)
	if err != nil {
		b.Fatal(err)
	}
	defer device.Close()

	const batch = 32
	bufs := make([][]byte, batch)
	for i := range bufs {
		bufs[i] = make([]byte, mtu+128)
	}
	sizes := make([]int, batch)

	b.ReportAllocs()
	b.SetBytes(int64(mtu * batch))
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		var list stack.PacketBufferList
		for j := 0; j < batch; j++ {
			list.PushBack(packet(mtu))
		}
		if _, err := device.endpoint.WritePackets(list); err != nil {
			b.Fatalf("queueing: %v", err)
		}
		list.Reset()
		read := 0
		for read < batch {
			n, err := device.Read(bufs[read:], sizes[read:], 0)
			if err != nil {
				b.Fatalf("reading: %v", err)
			}
			read += n
		}
	}
}

// BenchmarkForward measures the splice itself: what it costs to move a megabyte
// between two connections, which is every byte in both directions.
func BenchmarkForward(b *testing.B) {
	payload := make([]byte, 1<<20)

	b.ReportAllocs()
	b.SetBytes(int64(len(payload)))
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		clientSide, tunnelSide := net.Pipe()
		remoteSide, originSide := net.Pipe()

		go forward(tunnelSide, remoteSide, tcpIdleTimeout)

		var wg sync.WaitGroup
		wg.Add(2)
		go func() {
			defer wg.Done()
			defer originSide.Close()
			_, _ = originSide.Write(payload)
		}()
		go func() {
			defer wg.Done()
			_, _ = io.CopyN(io.Discard, clientSide, int64(len(payload)))
			clientSide.Close()
		}()
		wg.Wait()
	}
}

// BenchmarkEndToEndDownload is the whole thing: a real wireguard-go client, a
// real Relay endpoint, and a destination that exists only outside the tunnel.
//
// Every other benchmark here isolates one stage. This one pays for all of them
// at once -- the client's encryption, a UDP round trip, the endpoint's
// decryption, gVisor's TCP reassembly, the forwarder's dial and the splice --
// which is the number that corresponds to what a person actually sees.
//
// It runs on one machine, so it measures CPU rather than a link: the answer is
// "how fast could the phone go if the radio were free", which is exactly the
// question, because measurement has repeatedly found the phone's CPU to be the
// limit rather than the air.
func BenchmarkEndToEndDownload(b *testing.B) {
	const payloadSize = 4 << 20

	destination := httpServer(b, strings.Repeat("x", payloadSize))

	serverPrivate, serverPublic := keyPair(b)
	clientPrivate, clientPublic := keyPair(b)
	port := freeUDPPort(b)

	endpoint, err := Start(fmt.Sprintf(`private_key=%s
listen_port=%d
public_key=%s
allowed_ip=10.13.37.2/32
`, serverPrivate, port, clientPublic))
	if err != nil {
		b.Fatalf("endpoint did not start: %v", err)
	}
	defer endpoint.Stop()

	client, clientNet := wireguardClient(b, clientPrivate, serverPublic, port)
	defer client.Close()

	httpClient := &http.Client{
		Transport: &http.Transport{DialContext: clientNet.DialContext},
		Timeout:   60 * time.Second,
	}

	// The handshake takes a moment, and timing it would measure the handshake.
	warm := time.Now().Add(20 * time.Second)
	for time.Now().Before(warm) {
		response, err := httpClient.Get("http://" + destination + "/")
		if err == nil {
			_, _ = io.Copy(io.Discard, response.Body)
			response.Body.Close()
			break
		}
		time.Sleep(250 * time.Millisecond)
	}

	b.ReportAllocs()
	b.SetBytes(payloadSize)
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		response, err := httpClient.Get("http://" + destination + "/")
		if err != nil {
			b.Fatalf("the tunnel stopped carrying traffic: %v", err)
		}
		n, err := io.Copy(io.Discard, response.Body)
		response.Body.Close()
		if err != nil {
			b.Fatalf("reading through the tunnel: %v", err)
		}
		if n != payloadSize {
			b.Fatalf("short read through the tunnel: %d of %d", n, payloadSize)
		}
	}
}
