#!/usr/bin/env bash
# Measure what a Relay session actually delivers, from the laptop.
#
# Why this exists: every performance claim made about Relay before this was made
# from a single sample on a mobile link, and a single sample on a mobile link
# measures the weather. Two arms taken ten minutes apart are not comparable --
# the earlier session watched a "baseline" move 6.28 -> 13.88 -> 10.72 Mbps
# without anything changing but the clock.
#
# So this takes ONE sample and prints one line. The comparison is built by the
# caller alternating arms -- A B A B A B -- and taking medians, which is the
# only way a difference smaller than the link's own drift can be seen at all.
#
# Usage:  bench-tunnel.sh <label> [seconds]
# Output: a TSV line: label  down_mbps  dns_ms  rtt_ms  conn_ms
#
# Every field is independent on purpose. Throughput and DNS answer different
# questions, and the leak-protection question in particular is a DNS question
# wearing a throughput costume: pinning the resolver into the tunnel cannot slow
# an established transfer, but it can add a tunnel round trip to every name a
# page looks up.
set -u

label="${1:?usage: bench-tunnel.sh <label> [seconds]}"
seconds="${2:-15}"

# Hetzner rather than Cloudflare: curl's schannel backend on this laptop fails
# Cloudflare's certificate with CRYPT_E_REVOCATION_OFFLINE, and a benchmark that
# measures a TLS failure is worse than no benchmark.
URL="https://ash-speed.hetzner.com/100MB.bin"
PING_HOST="1.1.1.1"

# Bytes moved inside a fixed window, rather than time to move a fixed size.
# On a 2 Mbps link a fixed size either takes minutes or finishes before TCP has
# opened its window; a fixed window measures the same thing at any speed.
read -r size time <<<"$(curl -sS --max-time "$seconds" -o /dev/null \
  -w '%{size_download} %{time_total}' "$URL" 2>/dev/null || echo "0 1")"
down_mbps=$(awk -v s="$size" -v t="$time" 'BEGIN{ if (t>0) printf "%.2f", (s*8)/(t*1000000); else print "0" }')

# A cold lookup, not a cached one: Windows caches, so asking twice measures the
# cache. A name nobody has resolved recently is what a page load actually pays.
cold="bench-$(date +%s%N | tail -c 7).example.com"
dns_ms=$(python - "$cold" <<'PY' 2>/dev/null || echo "0"
import socket, sys, time
name = sys.argv[1]
start = time.perf_counter()
try:
    socket.getaddrinfo(name, 80)
except Exception:
    pass  # NXDOMAIN is a fine answer; the round trip is what is being timed
print("%.1f" % ((time.perf_counter() - start) * 1000))
PY
)

# Latency to a fixed address, so the tunnel's own cost is visible separately
# from whatever the far end is doing.
rtt_ms=$(ping -n 5 "$PING_HOST" 2>/dev/null | grep -oE 'Average = [0-9]+ms' | grep -oE '[0-9]+' || echo "0")
[ -z "$rtt_ms" ] && rtt_ms=0

# TCP connect time on its own: this is what a WFP filter at ALE_AUTH_CONNECT
# could plausibly charge, and the only place it could show up.
conn_ms=$(curl -sS --max-time 20 -o /dev/null -w '%{time_connect}' \
  "https://ash-speed.hetzner.com/" 2>/dev/null || echo 0)
conn_ms=$(awk -v c="$conn_ms" 'BEGIN{printf "%.1f", c*1000}')

printf '%s\t%s\t%s\t%s\t%s\n' "$label" "$down_mbps" "$dns_ms" "$rtt_ms" "$conn_ms"
