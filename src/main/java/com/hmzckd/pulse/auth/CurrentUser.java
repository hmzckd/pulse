package com.hmzckd.pulse.auth;

import java.util.UUID;

import org.springframework.security.oauth2.jwt.Jwt;

public record CurrentUser(UUID id, String username) {

	public static CurrentUser from(Jwt jwt) {
		return new CurrentUser(UUID.fromString(jwt.getClaimAsString("uid")), jwt.getSubject());
	}
}
