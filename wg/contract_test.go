package relaywg

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

// The phone assembles the endpoint's configuration in Kotlin and this package
// consumes it. Nothing in either build has ever checked that the two agree, and
// they did not: WgConfig emitted the wg-quick INI a person reads while IpcSet
// only takes the flat hex dialect, so Full Mode could not start on any phone.
//
// /shared/test-vectors.json now carries the exact string. Android asserts it
// produces that; this asserts a real device accepts it. Both halves are needed
// -- agreeing on a string no device takes is worth nothing, and so is a valid
// string the phone never produces.

type wgServerConfigVector struct {
	Keys struct {
		ServerPrivateKey string `json:"serverPrivateKey"`
		ClientPublicKey  string `json:"clientPublicKey"`
	} `json:"keys"`
	EndpointPort   int    `json:"endpointPort"`
	ClientTunnelIP string `json:"clientTunnelIp"`
	ServerTunnelIP string `json:"serverTunnelIp"`
	IPC            string `json:"ipc"`
}

func loadServerConfigVector(t *testing.T) wgServerConfigVector {
	t.Helper()
	path := filepath.Join("..", "shared", "test-vectors.json")
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("reading %s: %v", path, err)
	}
	var file struct {
		WgServerConfig wgServerConfigVector `json:"wgServerConfig"`
	}
	if err := json.Unmarshal(raw, &file); err != nil {
		t.Fatalf("parsing %s: %v", path, err)
	}
	if file.WgServerConfig.IPC == "" {
		t.Fatal("test-vectors.json has no wgServerConfig.ipc")
	}
	return file.WgServerConfig
}

func TestTheConfigurationThePhoneSendsIsAccepted(t *testing.T) {
	vector := loadServerConfigVector(t)

	// The vector's port is the real default (51820), which a CI runner may
	// already have in use and which needs no privilege to bind but does need to
	// be free. Swapping in a free one keeps the test about the dialect rather
	// than about the runner's luck.
	config := strings.Replace(vector.IPC,
		"listen_port="+strconv.Itoa(vector.EndpointPort),
		"listen_port="+strconv.Itoa(freeUDPPort(t)), 1)

	endpoint, err := Start(config)
	if err != nil {
		t.Fatalf("the phone's own configuration was rejected: %v", err)
	}
	defer endpoint.Stop()
}

func TestTheVectorRoutesThePeerToTheAddressTheClientUses(t *testing.T) {
	// allowed_ip is cryptokey routing, not decoration: a peer's packets are
	// dropped unless their source falls inside it. If the phone ever writes a
	// different subnet than the client gives itself, the tunnel handshakes and
	// then carries nothing -- the hardest failure of all to read from outside.
	vector := loadServerConfigVector(t)

	if want := "allowed_ip=" + vector.ClientTunnelIP + "/32"; !strings.Contains(vector.IPC, want) {
		t.Errorf("vector does not route the peer at %s:\n%s", vector.ClientTunnelIP, vector.IPC)
	}
	if vector.ServerTunnelIP != tunnelAddress {
		t.Errorf("this endpoint answers on %s, the contract says %s",
			tunnelAddress, vector.ServerTunnelIP)
	}
}

func TestTheConfigurationIsTheIpcDialectAndNotIni(t *testing.T) {
	// The specific mistake, named. IpcSet's errors do not distinguish "wrong
	// dialect" from "bad key", so without this the next person to reach for the
	// readable form gets a WG_START_FAILED and no clue why.
	vector := loadServerConfigVector(t)

	for _, wrong := range []string{"[Interface]", "[Peer]", "PrivateKey", "ListenPort", "AllowedIPs"} {
		if strings.Contains(vector.IPC, wrong) {
			t.Errorf("%q is wg-quick INI; IpcSet does not read it", wrong)
		}
	}
	for _, required := range []string{"private_key=", "listen_port=", "public_key=", "allowed_ip="} {
		if !strings.Contains(vector.IPC, required) {
			t.Errorf("missing %q", required)
		}
	}
	// Hex, not base64: the difference that produces a device which handshakes
	// with nobody and reports no error at all.
	for _, line := range strings.Split(strings.TrimSpace(vector.IPC), "\n") {
		name, value, _ := strings.Cut(line, "=")
		if name != "private_key" && name != "public_key" {
			continue
		}
		if len(value) != 64 {
			t.Errorf("%s is %d characters; a hex WireGuard key is 64", name, len(value))
		}
		if strings.ContainsAny(value, "+/=") || strings.ToLower(value) != value {
			t.Errorf("%s looks like base64, not hex: %q", name, value)
		}
	}
}
