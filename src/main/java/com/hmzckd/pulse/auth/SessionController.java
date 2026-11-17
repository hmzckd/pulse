package com.hmzckd.pulse.auth;

import java.time.Instant;
import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
class SessionController {

	private final UserRepository users;
	private final TokenService tokens;

	SessionController(UserRepository users, TokenService tokens) {
		this.users = users;
		this.tokens = tokens;
	}

	@PostMapping
	SessionResponse create(@RequestBody CreateSessionRequest request) {
		String username = Usernames.normalize(request.username());
		UUID userId = users.findOrCreate(username);
		TokenService.IssuedToken token = tokens.issue(userId, username);
		return new SessionResponse(token.value(), username, token.expiresAt());
	}

	record CreateSessionRequest(String username) {
	}

	record SessionResponse(String token, String username, Instant expiresAt) {
	}
}
