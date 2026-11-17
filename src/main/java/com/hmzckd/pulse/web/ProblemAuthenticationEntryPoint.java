package com.hmzckd.pulse.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private static final String BODY = """
			{"type":"about:blank","title":"Unauthorized","status":401,"detail":"Missing, invalid or expired token"}""";

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
			throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.getOutputStream().write(BODY.getBytes(StandardCharsets.UTF_8));
	}
}
