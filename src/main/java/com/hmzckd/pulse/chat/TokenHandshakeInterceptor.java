package com.hmzckd.pulse.chat;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import com.hmzckd.pulse.auth.CurrentUser;

@Component
class TokenHandshakeInterceptor implements HandshakeInterceptor {

	static final String USER_ATTRIBUTE = "user";

	private final JwtDecoder jwtDecoder;

	TokenHandshakeInterceptor(JwtDecoder jwtDecoder) {
		this.jwtDecoder = jwtDecoder;
	}

	@Override
	public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
			WebSocketHandler wsHandler, Map<String, Object> attributes) {
		String token = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("token");
		if (token == null || token.isBlank()) {
			response.setStatusCode(HttpStatus.UNAUTHORIZED);
			return false;
		}
		try {
			attributes.put(USER_ATTRIBUTE, CurrentUser.from(jwtDecoder.decode(token)));
			return true;
		}
		catch (JwtException | IllegalArgumentException ex) {
			response.setStatusCode(HttpStatus.UNAUTHORIZED);
			return false;
		}
	}

	@Override
	public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
			Exception exception) {
	}
}
