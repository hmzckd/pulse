package com.hmzckd.pulse.chat;

import java.time.Duration;

final class RateLimiter {

	private final int limit;
	private final long windowNanos;
	private double tokens;
	private long updatedAt;

	RateLimiter(int limit, Duration window, long nowNanos) {
		this.limit = limit;
		this.windowNanos = window.toNanos();
		this.tokens = limit;
		this.updatedAt = nowNanos;
	}

	synchronized boolean tryAcquire(long nowNanos) {
		tokens = Math.min(limit, tokens + (double) (nowNanos - updatedAt) * limit / windowNanos);
		updatedAt = nowNanos;
		if (tokens < 1) {
			return false;
		}
		tokens -= 1;
		return true;
	}
}
