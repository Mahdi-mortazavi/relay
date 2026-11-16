//go:build !amd64 && !arm64 && !386 && !arm

package main

import (
	"errors"
	"net/netip"
	"strings"
)

type leakOptions struct {
	tunLUID         uint64
	resolvers       []netip.Addr
	allowLAN        bool
	allowDiscovery  bool
	discoveryApp    string
	excludeApps     []string
	excludePrefixes []netip.Prefix
	persistent      bool
}

func parsePrefixExclusions(list string) ([]netip.Prefix, error) {
	if strings.TrimSpace(list) == "" {
		return nil, nil
	}
	return nil, errors.New("CIDR exclusions are not supported on this architecture")
}

func parseAppExclusions(list string) ([]string, error) {
	if strings.TrimSpace(list) == "" {
		return nil, nil
	}
	return nil, errors.New("app exclusions are not supported on this architecture")
}

func enableLeakProtection(_ leakOptions) (func(), error) {
	return nil, errors.New("leak protection is not implemented on this architecture")
}

func clearPersistentKillSwitch() error {
	return errors.New("kill-switch cleanup is not implemented on this architecture")
}
