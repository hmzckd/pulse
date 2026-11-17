package com.hmzckd.pulse.room;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.hmzckd.pulse.IntegrationTest;
import com.hmzckd.pulse.TestUsers;
import com.jayway.jsonpath.JsonPath;

@IntegrationTest
class RoomTests {

	@Autowired
	MockMvc mockMvc;

	@Test
	void creatorGetsLocationAndBecomesMember() throws Exception {
		String ali = TestUsers.uniqueName("ali");
		String token = TestUsers.login(mockMvc, ali);

		String body = createRoom(token, "  General  ")
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/rooms/")))
				.andExpect(jsonPath("$.name").value("General"))
				.andExpect(jsonPath("$.createdBy").value(ali))
				.andReturn().getResponse().getContentAsString();
		String roomId = JsonPath.read(body, "$.id");

		mockMvc.perform(get("/api/rooms/" + roomId).header("Authorization", TestUsers.bearer(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("General"));
		mockMvc.perform(get("/api/rooms/mine").header("Authorization", TestUsers.bearer(token)))
				.andExpect(jsonPath("$[*].id", hasItem(roomId)));
	}

	@Test
	void anotherUserSeesTheRoomAndCanJoinIt() throws Exception {
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String ayseToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ayse"));
		String roomId = JsonPath.read(createRoom(aliToken, "Games").andReturn().getResponse().getContentAsString(),
				"$.id");

		mockMvc.perform(get("/api/rooms").header("Authorization", TestUsers.bearer(ayseToken)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].id", hasItem(roomId)));
		mockMvc.perform(get("/api/rooms/mine").header("Authorization", TestUsers.bearer(ayseToken)))
				.andExpect(jsonPath("$[*].id", not(hasItem(roomId))));

		join(ayseToken, roomId).andExpect(status().isNoContent());

		mockMvc.perform(get("/api/rooms/mine").header("Authorization", TestUsers.bearer(ayseToken)))
				.andExpect(jsonPath("$[*].id", hasItem(roomId)));
	}

	@Test
	void joiningTwiceIsHarmless() throws Exception {
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String roomId = JsonPath.read(createRoom(aliToken, "Music").andReturn().getResponse().getContentAsString(),
				"$.id");

		join(aliToken, roomId).andExpect(status().isNoContent());
		join(aliToken, roomId).andExpect(status().isNoContent());

		mockMvc.perform(get("/api/rooms/mine").header("Authorization", TestUsers.bearer(aliToken)))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(roomId));
	}

	@Test
	void unknownRoomIs404Problem() throws Exception {
		String token = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String missing = UUID.randomUUID().toString();

		join(token, missing)
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		mockMvc.perform(get("/api/rooms/" + missing).header("Authorization", TestUsers.bearer(token)))
				.andExpect(status().isNotFound());
	}

	@Test
	void invalidRoomNamesAre400() throws Exception {
		String token = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));

		createRoom(token, "   ").andExpect(status().isBadRequest());
		createRoom(token, "x".repeat(51)).andExpect(status().isBadRequest());
	}

	private ResultActions createRoom(String token, String name) throws Exception {
		return mockMvc.perform(post("/api/rooms")
				.header("Authorization", TestUsers.bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"" + name + "\"}"));
	}

	private ResultActions join(String token, String roomId) throws Exception {
		return mockMvc.perform(post("/api/rooms/" + roomId + "/members")
				.header("Authorization", TestUsers.bearer(token)));
	}
}
