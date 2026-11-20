package com.hmzckd.pulse;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

public final class TestApi {

	private TestApi() {
	}

	public static String createRoom(MockMvc mockMvc, String token, String name) throws Exception {
		String body = mockMvc.perform(post("/api/rooms")
						.header("Authorization", TestUsers.bearer(token))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"" + name + "\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	public static void join(MockMvc mockMvc, String token, String roomId) throws Exception {
		mockMvc.perform(post("/api/rooms/" + roomId + "/members").header("Authorization", TestUsers.bearer(token)))
				.andExpect(status().isNoContent());
	}
}
