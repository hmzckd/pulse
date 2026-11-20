package com.hmzckd.pulse.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class RateLimiterTests {

	private static final long SECOND = Duration.ofSeconds(1).toNanos();

	@Test
	void allowsTwentyThenRejects() {
		RateLimiter limiter = new RateLimiter(20, Duration.ofSeconds(10), 0);
		for (int i = 0; i < 20; i++) {
			assertThat(limiter.tryAcquire(0)).as("message " + (i + 1)).isTrue();
		}
		assertThat(limiter.tryAcquire(0)).isFalse();
	}

	@Test
	void refillsTwoPerSecond() {
		RateLimiter limiter = new RateLimiter(20, Duration.ofSeconds(10), 0);
		for (int i = 0; i < 20; i++) {
			limiter.tryAcquire(0);
		}
		assertThat(limiter.tryAcquire(SECOND / 4)).isFalse();
		assertThat(limiter.tryAcquire(SECOND / 2)).isTrue();
		assertThat(limiter.tryAcquire(SECOND / 2)).isFalse();
	}

	@Test
	void neverSavesUpMoreThanTheLimit() {
		RateLimiter limiter = new RateLimiter(20, Duration.ofSeconds(10), 0);
		long anHourLater = 3600 * SECOND;
		for (int i = 0; i < 20; i++) {
			assertThat(limiter.tryAcquire(anHourLater)).isTrue();
		}
		assertThat(limiter.tryAcquire(anHourLater)).isFalse();
	}
}
