package relaywg

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"time"
)

var errQuotaExceeded = errors.New("relaywg: session traffic quota exceeded")

type trafficDirection uint8

const (
	dirUp trafficDirection = iota
	dirDown
)

type tokenBucket struct {
	mu     sync.Mutex
	rate   int64 // bytes/sec, 0 = unlimited
	tokens float64
	burst  float64
	last   time.Time
}

func (b *tokenBucket) setRate(bytesPerSec int64) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if bytesPerSec < 0 {
		bytesPerSec = 0
	}
	b.rate = bytesPerSec
	if bytesPerSec > 0 {
		// Allow at least 64 KB burst so full read buffers don't stall needlessly.
		burst := float64(bytesPerSec)
		if burst < 64*1024 {
			burst = 64 * 1024
		}
		b.burst = burst
		b.tokens = burst
		b.last = time.Now()
	} else {
		b.burst = 0
		b.tokens = 0
	}
}

func (b *tokenBucket) take(ctx context.Context, n int) error {
	if n <= 0 {
		return nil
	}
	need := float64(n)
	for {
		b.mu.Lock()
		rate := b.rate
		if rate <= 0 {
			b.mu.Unlock()
			return nil
		}
		now := time.Now()
		if b.last.IsZero() {
			b.last = now
			b.tokens = b.burst
		} else {
			elapsed := now.Sub(b.last).Seconds()
			if elapsed > 0 {
				b.tokens += elapsed * float64(rate)
				if b.tokens > b.burst {
					b.tokens = b.burst
				}
				b.last = now
			}
		}

		// Consume up to burst when large packets exceed burst.
		req := need
		if req > b.burst {
			req = b.burst
		}
		if b.tokens >= req {
			b.tokens -= need
			b.mu.Unlock()
			return nil
		}
		waitSec := (req - b.tokens) / float64(rate)
		b.mu.Unlock()

		waitDur := time.Duration(waitSec * float64(time.Second))
		if waitDur < time.Millisecond {
			waitDur = time.Millisecond
		}
		if waitDur > 100*time.Millisecond {
			waitDur = 100 * time.Millisecond
		}
		timer := time.NewTimer(waitDur)
		select {
		case <-ctx.Done():
			timer.Stop()
			return ctx.Err()
		case <-timer.C:
		}
	}
}

// trafficLimiter enforces optional upload/download throttling and total byte quota.
type trafficLimiter struct {
	up         tokenBucket
	down       tokenBucket
	quotaBytes atomic.Int64
	usedBytes  atomic.Int64
	exceeded   atomic.Bool
}

func newTrafficLimiter() *trafficLimiter {
	return &trafficLimiter{}
}

func (l *trafficLimiter) setLimits(upBytesPerSec, downBytesPerSec, quotaBytes int64) {
	if l == nil {
		return
	}
	l.up.setRate(upBytesPerSec)
	l.down.setRate(downBytesPerSec)
	if quotaBytes < 0 {
		quotaBytes = 0
	}
	l.quotaBytes.Store(quotaBytes)
	l.exceeded.Store(quotaBytes > 0 && l.usedBytes.Load() >= quotaBytes)
}

func (l *trafficLimiter) resetUsage() {
	if l == nil {
		return
	}
	l.usedBytes.Store(0)
	l.exceeded.Store(false)
}

func (l *trafficLimiter) checkQuota() error {
	if l == nil {
		return nil
	}
	q := l.quotaBytes.Load()
	if q > 0 && (l.exceeded.Load() || l.usedBytes.Load() >= q) {
		l.exceeded.Store(true)
		return errQuotaExceeded
	}
	return nil
}

func (l *trafficLimiter) consume(ctx context.Context, dir trafficDirection, n int) error {
	if l == nil || n <= 0 {
		return nil
	}
	q := l.quotaBytes.Load()
	total := l.usedBytes.Add(int64(n))
	if q > 0 && total > q {
		l.exceeded.Store(true)
		return errQuotaExceeded
	}
	if dir == dirUp {
		return l.up.take(ctx, n)
	}
	return l.down.take(ctx, n)
}

func (l *trafficLimiter) status() (used, quota int64, exceeded bool) {
	if l == nil {
		return 0, 0, false
	}
	used = l.usedBytes.Load()
	quota = l.quotaBytes.Load()
	exceeded = l.exceeded.Load() || (quota > 0 && used >= quota)
	return used, quota, exceeded
}
