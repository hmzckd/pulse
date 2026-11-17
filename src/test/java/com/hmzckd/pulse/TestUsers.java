package com.hmzckd.pulse;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

public final class TestUsers {

	private static final AtomicInteger COUNTER = new AtomicInteger();

	private TestUsers() {
	}

	public static String uniqueName(String prefix) {
		return prefix + "_" + System.nanoTime() % 100_000 + COUNTER.incrementAndGet();
	}

	public static String login(MockMvc mockMvc, String username) throws Exception {
		String body = mockMvc.perform(post("/api/sessions")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"username\":\"" + username + "\"}"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.token");
	}

	public static String bearer(String token) {
		return "Bearer " + token;
	}
}
