package com.hmzckd.pulse.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pulse.jwt")
public record JwtProperties(String secret, Duration ttl) {

	public JwtProperties {
		if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
			throw new IllegalArgumentException("pulse.jwt.secret must be at least 32 bytes (set PULSE_JWT_SECRET)");
		}
		if (ttl == null || ttl.isNegative() || ttl.isZero()) {
			throw new IllegalArgumentException("pulse.jwt.ttl must be positive");
		}
	}
}
