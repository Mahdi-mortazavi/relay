package relaywg

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

const dnsInterceptTimeout = 8 * time.Second

type dnsConfig struct {
	customDNS string
	dohURL    string
	client    *http.Client
}

// tunnelDNS intercepts DNS queries addressed to Relay's tunnel resolver (port 53)
// and resolves them via either an in-tunnel DoH endpoint or a custom DNS IP,
// dialing outbound exclusively through the netTun's active upstream.
type tunnelDNS struct {
	mu  sync.RWMutex
	cfg *dnsConfig
}

func newTunnelDNS() *tunnelDNS {
	return &tunnelDNS{}
}

func (t *tunnelDNS) configure(customDNS, dohURL string, dial func(network, address string) (net.Conn, error)) error {
	customDNS = strings.TrimSpace(customDNS)
	dohURL = strings.TrimSpace(dohURL)

	if customDNS == "" && dohURL == "" {
		t.mu.Lock()
		t.cfg = nil
		t.mu.Unlock()
		return nil
	}

	var normalizedDNS string
	if customDNS != "" {
		var err error
		normalizedDNS, err = normalizeDNSAddr(customDNS)
		if err != nil {
			return err
		}
	}

	var client *http.Client
	if dohURL != "" {
		u, err := url.Parse(dohURL)
		if err != nil || u.Scheme != "https" || u.Host == "" {
			return fmt.Errorf("relaywg: invalid DoH URL %q (must be https://...)", dohURL)
		}
		transport := &http.Transport{
			DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				host, port, err := net.SplitHostPort(addr)
				if err != nil {
					return nil, err
				}
				if ip := net.ParseIP(host); ip != nil {
					return dial(network, net.JoinHostPort(ip.String(), port))
				}
				// Bootstrap hostname via customDNS (or standard DNS through upstream)
				bootstrapDNS := normalizedDNS
				if bootstrapDNS == "" {
					bootstrapDNS = "1.1.1.1:53"
				}
				resolved, err := lookupHostAOrAAAA(ctx, dial, bootstrapDNS, host)
				if err != nil {
					return nil, err
				}
				return dial(network, net.JoinHostPort(resolved, port))
			},
			ForceAttemptHTTP2:   true,
			IdleConnTimeout:     30 * time.Second,
			TLSHandshakeTimeout: 8 * time.Second,
		}
		client = &http.Client{
			Transport: transport,
			Timeout:   dnsInterceptTimeout,
		}
	}

	t.mu.Lock()
	t.cfg = &dnsConfig{
		customDNS: normalizedDNS,
		dohURL:    dohURL,
		client:    client,
	}
	t.mu.Unlock()
	return nil
}

func (t *tunnelDNS) active() bool {
	if t == nil {
		return false
	}
	t.mu.RLock()
	has := t.cfg != nil
	t.mu.RUnlock()
	return has
}

func (t *tunnelDNS) hasDoH() bool {
	if t == nil {
		return false
	}
	t.mu.RLock()
	has := t.cfg != nil && t.cfg.dohURL != ""
	t.mu.RUnlock()
	return has
}

func (t *tunnelDNS) resolveWire(ctx context.Context, query []byte, dial func(network, address string) (net.Conn, error)) ([]byte, error) {
	t.mu.RLock()
	cfg := t.cfg
	t.mu.RUnlock()
	if cfg == nil {
		return nil, errors.New("relaywg: tunnel DNS override not configured")
	}
	if len(query) < 12 || len(query) > 4096 {
		return nil, errors.New("relaywg: malformed DNS query size")
	}

	if cfg.dohURL != "" && cfg.client != nil {
		resp, err := queryDoH(ctx, cfg.client, cfg.dohURL, query)
		if err == nil {
			return resp, nil
		}
		if cfg.customDNS == "" {
			return nil, err
		}
	}
	if cfg.customDNS != "" {
		return queryUDPWire(ctx, dial, cfg.customDNS, query)
	}
	return nil, errors.New("relaywg: no DNS resolver configured")
}

func (t *tunnelDNS) serveUDP(tun *netTun, clientConn net.Conn, entry *flowEntry) {
	var wg sync.WaitGroup
	defer tun.flows.finish(entry)
	defer wg.Wait()
	defer clientConn.Close()

	buf := make([]byte, 4096)
	for {
		_ = clientConn.SetReadDeadline(time.Now().Add(udpIdleTimeout))
		n, err := clientConn.Read(buf)
		if err != nil {
			return
		}
		if n == 0 {
			continue
		}
		tun.flows.addUp(entry, int64(n))
		if err := tun.limiter.consume(tun.ctx, dirUp, n); err != nil {
			return
		}

		q := make([]byte, n)
		copy(q, buf[:n])
		wg.Add(1)
		go func(payload []byte) {
			defer wg.Done()
			ctx, cancel := context.WithTimeout(tun.ctx, dnsInterceptTimeout)
			defer cancel()
			ans, err := t.resolveWire(ctx, payload, tun.dial)
			if err != nil || len(ans) == 0 {
				return
			}
			if err := tun.limiter.consume(tun.ctx, dirDown, len(ans)); err != nil {
				return
			}
			tun.flows.addDown(entry, int64(len(ans)))
			_, _ = clientConn.Write(ans)
		}(q)
	}
}

func (t *tunnelDNS) serveTCP(tun *netTun, clientConn net.Conn, entry *flowEntry) {
	defer clientConn.Close()
	defer tun.flows.finish(entry)

	var lenBuf [2]byte
	for {
		_ = clientConn.SetReadDeadline(time.Now().Add(tcpIdleTimeout))
		if _, err := io.ReadFull(clientConn, lenBuf[:]); err != nil {
			return
		}
		qLen := int(binary.BigEndian.Uint16(lenBuf[:]))
		if qLen < 12 || qLen > 8192 {
			return
		}
		query := make([]byte, qLen)
		if _, err := io.ReadFull(clientConn, query); err != nil {
			return
		}
		tun.flows.addUp(entry, int64(qLen+2))
		if err := tun.limiter.consume(tun.ctx, dirUp, qLen+2); err != nil {
			return
		}

		ctx, cancel := context.WithTimeout(tun.ctx, dnsInterceptTimeout)
		ans, err := t.resolveWire(ctx, query, tun.dial)
		cancel()
		if err != nil || len(ans) == 0 || len(ans) > 65535 {
			return
		}
		if err := tun.limiter.consume(tun.ctx, dirDown, len(ans)+2); err != nil {
			return
		}
		tun.flows.addDown(entry, int64(len(ans)+2))

		var outLen [2]byte
		binary.BigEndian.PutUint16(outLen[:], uint16(len(ans)))
		if _, err := clientConn.Write(outLen[:]); err != nil {
			return
		}
		if _, err := clientConn.Write(ans); err != nil {
			return
		}
	}
}

func queryDoH(ctx context.Context, client *http.Client, endpoint string, wireQuery []byte) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(wireQuery))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/dns-message")
	req.Header.Set("Accept", "application/dns-message")

	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("relaywg: DoH HTTP status %d", resp.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 65535))
	if err != nil {
		return nil, err
	}
	if len(body) < 12 {
		return nil, errors.New("relaywg: truncated DoH DNS response")
	}
	return body, nil
}

func queryUDPWire(ctx context.Context, dial func(network, address string) (net.Conn, error), server string, query []byte) ([]byte, error) {
	conn, err := dial("udp", server)
	if err != nil {
		return nil, err
	}
	defer conn.Close()

	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	} else {
		_ = conn.SetDeadline(time.Now().Add(dnsInterceptTimeout))
	}
	if _, err := conn.Write(query); err != nil {
		return nil, err
	}
	buf := make([]byte, 4096)
	n, err := conn.Read(buf)
	if err != nil {
		return nil, err
	}
	if n < 12 {
		return nil, errors.New("relaywg: truncated UDP DNS response")
	}
	return buf[:n], nil
}

func normalizeDNSAddr(raw string) (string, error) {
	if ip := net.ParseIP(raw); ip != nil {
		return net.JoinHostPort(ip.String(), "53"), nil
	}
	host, port, err := net.SplitHostPort(raw)
	if err != nil {
		return "", fmt.Errorf("relaywg: invalid DNS server %q: %w", raw, err)
	}
	if net.ParseIP(host) == nil {
		return "", fmt.Errorf("relaywg: DNS server host %q must be an IP address", host)
	}
	return net.JoinHostPort(host, port), nil
}

// lookupHostAOrAAAA performs a minimal RFC 1035 A lookup using the upstream tunnel
// so DoH server hostnames like dns.google resolve inside the phone's own VPN.
func lookupHostAOrAAAA(ctx context.Context, dial func(network, address string) (net.Conn, error), dnsServer, host string) (string, error) {
	q := buildSimpleAQuery(host, 0x4242)
	ans, err := queryUDPWire(ctx, dial, dnsServer, q)
	if err != nil {
		return "", err
	}
	ip := extractFirstA(ans)
	if ip == nil {
		return "", fmt.Errorf("relaywg: could not resolve DoH host %q via %s", host, dnsServer)
	}
	return ip.String(), nil
}

func buildSimpleAQuery(name string, id uint16) []byte {
	var b bytes.Buffer
	var hdr [12]byte
	binary.BigEndian.PutUint16(hdr[0:2], id)
	binary.BigEndian.PutUint16(hdr[2:4], 0x0100) // RD=1
	binary.BigEndian.PutUint16(hdr[4:6], 1)      // QDCOUNT=1
	b.Write(hdr[:])
	for _, label := range strings.Split(strings.TrimSuffix(name, "."), ".") {
		if len(label) == 0 || len(label) > 63 {
			continue
		}
		b.WriteByte(byte(len(label)))
		b.WriteString(label)
	}
	b.WriteByte(0)
	b.Write([]byte{0x00, 0x01, 0x00, 0x01}) // TYPE=A, CLASS=IN
	return b.Bytes()
}

func extractFirstA(msg []byte) net.IP {
	if len(msg) < 12 {
		return nil
	}
	qdCount := int(binary.BigEndian.Uint16(msg[4:6]))
	anCount := int(binary.BigEndian.Uint16(msg[6:8]))
	off := 12
	for i := 0; i < qdCount; i++ {
		off = skipDNSName(msg, off)
		if off < 0 || off+4 > len(msg) {
			return nil
		}
		off += 4
	}
	for i := 0; i < anCount; i++ {
		off = skipDNSName(msg, off)
		if off < 0 || off+10 > len(msg) {
			return nil
		}
		typ := binary.BigEndian.Uint16(msg[off : off+2])
		rdlen := int(binary.BigEndian.Uint16(msg[off+8 : off+10]))
		off += 10
		if off+rdlen > len(msg) {
			return nil
		}
		if typ == 1 && rdlen == 4 {
			return net.IPv4(msg[off], msg[off+1], msg[off+2], msg[off+3])
		}
		off += rdlen
	}
	return nil
}

func skipDNSName(msg []byte, off int) int {
	for i := 0; i < len(msg); i++ {
		if off >= len(msg) {
			return -1
		}
		l := int(msg[off])
		if l == 0 {
			return off + 1
		}
		if l&0xC0 == 0xC0 {
			if off+2 > len(msg) {
				return -1
			}
			return off + 2
		}
		off += 1 + l
	}
	return -1
}
