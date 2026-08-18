package main

import "testing"

// The peer's key is what an endpoint update has to name, and it is read out of
// a configuration whose first line is the *device's* key. Confusing the two
// would send a well-formed update naming a peer that does not exist, which
// update_only turns into silence rather than an error -- so the tunnel would
// simply never follow the phone, with nothing logged to say why.
func TestPeerPublicKeyIsThePeersNotTheDevices(t *testing.T) {
	config := "private_key=aa\npublic_key=bb\nendpoint=192.168.1.14:51820\n" +
		"allowed_ip=0.0.0.0/0\npersistent_keepalive_interval=25\n"

	if got := peerPublicKey(config); got != "bb" {
		t.Fatalf("peerPublicKey = %q, want the peer's key %q", got, "bb")
	}
}

func TestPeerPublicKeyIsEmptyWhenThereIsNoPeer(t *testing.T) {
	if got := peerPublicKey("private_key=aa\n"); got != "" {
		t.Fatalf("peerPublicKey = %q, want empty", got)
	}
}

// Everything reaching [roam] came off a broadcast beacon, so it is attacker
// controlled. These are the cases that must not reach IpcSet at all -- a nil
// device is safe precisely because a rejected line returns before touching it,
// and this test would panic rather than fail if that stopped being true.
func TestRoamRejectsWhatIsNotAnAddressAndPort(t *testing.T) {
	for _, endpoint := range []string{
		"",
		// No port.
		"192.168.1.14",
		// A name, which would block on a lookup the tunnel is routing.
		"phone.local:51820",
		// Trailing junk.
		"192.168.1.14:51820 evil",
		// Not a port.
		"192.168.1.14:99999",
	} {
		if err := roam(nil, "bb", endpoint); err == nil {
			t.Errorf("roam accepted %q", endpoint)
		}
	}
}

func TestRoamRefusesWhenTheConfigurationNamedNoPeer(t *testing.T) {
	if err := roam(nil, "", "192.168.1.14:51820"); err == nil {
		t.Error("roam moved a peer that does not exist")
	}
}
