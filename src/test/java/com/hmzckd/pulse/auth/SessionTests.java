package com.hmzckd.pulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.hmzckd.pulse.IntegrationTest;
import com.hmzckd.pulse.TestUsers;

@IntegrationTest
class SessionTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcClient jdbc;

	@Test
	void loginReturnsTokenThatOpensProtectedEndpoints() throws Exception {
		String username = TestUsers.uniqueName("ali");

		login(username)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value(username))
				.andExpect(jsonPath("$.token").isString())
				.andExpect(jsonPath("$.expiresAt").isString());

		String token = TestUsers.login(mockMvc, username);
		mockMvc.perform(get("/api/rooms/mine").header("Authorization", TestUsers.bearer(token)))
				.andExpect(status().isOk());
	}

	@Test
	void sameUsernameTwiceIsTheSameUser() throws Exception {
		String username = TestUsers.uniqueName("ayse");

		TestUsers.login(mockMvc, username);
		TestUsers.login(mockMvc, username);

		assertThat(countUsers(username)).isEqualTo(1);
	}

	@Test
	void usernameIsTrimmedAndLowercasedEvenWithTurkishLocale() throws Exception {
		Locale original = Locale.getDefault();
		Locale.setDefault(Locale.forLanguageTag("tr-TR"));
		try {
			login("  ISIL_" + System.nanoTime() % 10_000 + "  ")
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.username").value(org.hamcrest.Matchers.startsWith("isil_")));
		}
		finally {
			Locale.setDefault(original);
		}
	}

	@Test
	void invalidUsernamesAreRejected() throws Exception {
		for (String bad : new String[] { "ab", "ayşe", "has space", "a".repeat(21) }) {
			login(bad)
					.andExpect(status().isBadRequest())
					.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		}
		mockMvc.perform(post("/api/sessions").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void requestWithoutTokenGetsProblem401() throws Exception {
		mockMvc.perform(get("/api/rooms"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", "Bearer"))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(401));
	}

	@Test
	void tokenSignedWithAnotherSecretIsRejected() throws Exception {
		String forged = sign("another-secret-that-is-at-least-32-bytes!", Instant.now().plusSeconds(3600));

		mockMvc.perform(get("/api/rooms").header("Authorization", TestUsers.bearer(forged)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void expiredTokenIsRejected() throws Exception {
		String expired = sign(IntegrationTest.JWT_SECRET, Instant.now().minusSeconds(3600));

		mockMvc.perform(get("/api/rooms").header("Authorization", TestUsers.bearer(expired)))
				.andExpect(status().isUnauthorized());
	}

	private ResultActions login(String username) throws Exception {
		return mockMvc.perform(post("/api/sessions")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"" + username + "\"}"));
	}

	private int countUsers(String username) {
		return jdbc.sql("SELECT count(*) FROM users WHERE username = :username")
				.param("username", username)
				.query(Integer.class)
				.single();
	}

	private static String sign(String secret, Instant expiresAt) {
		var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
		var claims = JwtClaimsSet.builder()
				.subject("mallory")
				.claim("uid", UUID.randomUUID().toString())
				.issuedAt(expiresAt.minusSeconds(60))
				.expiresAt(expiresAt)
				.build();
		var header = JwsHeader.with(MacAlgorithm.HS256).build();
		return NimbusJwtEncoder.withSecretKey(key).build()
				.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}
}
