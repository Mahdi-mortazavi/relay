package relaywg

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
)

// AccelCapabilities describes available WireGuard acceleration and kernel hooks.
type AccelCapabilities struct {
	KernelModulePresent  bool   `json:"kernelModulePresent"`
	WgQuickAvailable     bool   `json:"wgQuickAvailable"`
	ActiveEngine         string `json:"activeEngine"`
	RecommendedBatchSize int    `json:"recommendedBatchSize"`
}

// ProbeAccel inspects host capabilities (non-privileged, never spawns su).
func ProbeAccel() AccelCapabilities {
	caps := AccelCapabilities{
		ActiveEngine:         "wireguard-go+gvisor",
		RecommendedBatchSize: batchSize,
	}
	if _, err := os.Stat("/sys/module/wireguard"); err == nil {
		caps.KernelModulePresent = true
	}
	if _, err := exec.LookPath("wg"); err == nil {
		caps.WgQuickAvailable = true
	}
	return caps
}

// AccelStatusJSON returns JSON describing available acceleration on this device.
func AccelStatusJSON() string {
	raw, err := json.Marshal(ProbeAccel())
	if err != nil {
		return "{}"
	}
	return string(raw)
}

// BuildRootKernelCommands returns idempotent shell commands for rooted / Shizuku
// devices that want kernel NAT + TTL=65 carrier-tether normalization alongside Relay.
func BuildRootKernelCommands(listenPort int, ttl int) string {
	if ttl <= 0 {
		ttl = 65
	}
	return fmt.Sprintf(
		"iptables -t mangle -C POSTROUTING -j TTL --ttl-set %d 2>/dev/null || "+
			"iptables -t mangle -A POSTROUTING -j TTL --ttl-set %d\n"+
			"# Optional WireGuard kernel module check: modprobe wireguard && wg show (UDP %d)",
		ttl, ttl, listenPort,
	)
}
