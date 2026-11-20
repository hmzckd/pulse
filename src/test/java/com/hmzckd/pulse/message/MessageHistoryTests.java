package com.hmzckd.pulse.message;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.hmzckd.pulse.IntegrationTest;
import com.hmzckd.pulse.TestApi;
import com.hmzckd.pulse.TestUsers;

@IntegrationTest
class MessageHistoryTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	MessageService messages;

	@Autowired
	JdbcClient jdbc;

	@Test
	void historyIsPagedBackwardsBySeq() throws Exception {
		String ali = TestUsers.uniqueName("ali");
		String token = TestUsers.login(mockMvc, ali);
		String roomId = TestApi.createRoom(mockMvc, token, "Long chat");
		saveMessages(ali, roomId, 120);

		history(token, roomId, "")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.messages.length()").value(50))
				.andExpect(jsonPath("$.messages[0].seq").value(71))
				.andExpect(jsonPath("$.messages[49].seq").value(120))
				.andExpect(jsonPath("$.messages[49].text").value("m120"))
				.andExpect(jsonPath("$.messages[49].sender").value(ali))
				.andExpect(jsonPath("$.nextBefore").value(71));

		history(token, roomId, "?before=71")
				.andExpect(jsonPath("$.messages[0].seq").value(21))
				.andExpect(jsonPath("$.messages[49].seq").value(70))
				.andExpect(jsonPath("$.nextBefore").value(21));

		history(token, roomId, "?before=21")
				.andExpect(jsonPath("$.messages.length()").value(20))
				.andExpect(jsonPath("$.messages[0].seq").value(1))
				.andExpect(jsonPath("$.nextBefore").value(Matchers.nullValue()));
	}

	@Test
	void limitCanBeChosenWithinBounds() throws Exception {
		String ali = TestUsers.uniqueName("ali");
		String token = TestUsers.login(mockMvc, ali);
		String roomId = TestApi.createRoom(mockMvc, token, "Small pages");
		saveMessages(ali, roomId, 5);

		history(token, roomId, "?limit=2")
				.andExpect(jsonPath("$.messages[*].seq", Matchers.contains(4, 5)))
				.andExpect(jsonPath("$.nextBefore").value(4));
		history(token, roomId, "?limit=0").andExpect(status().isBadRequest());
		history(token, roomId, "?limit=101").andExpect(status().isBadRequest());
		history(token, roomId, "?before=0").andExpect(status().isBadRequest());
	}

	@Test
	void emptyRoomHasEmptyHistory() throws Exception {
		String token = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String roomId = TestApi.createRoom(mockMvc, token, "Quiet");

		history(token, roomId, "")
				.andExpect(jsonPath("$.messages.length()").value(0))
				.andExpect(jsonPath("$.nextBefore").value(Matchers.nullValue()));
	}

	@Test
	void onlyMembersCanReadHistory() throws Exception {
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String mehmetToken = TestUsers.login(mockMvc, TestUsers.uniqueName("mehmet"));
		String roomId = TestApi.createRoom(mockMvc, aliToken, "Private-ish");

		history(mehmetToken, roomId, "").andExpect(status().isForbidden());
		history(mehmetToken, UUID.randomUUID().toString(), "").andExpect(status().isNotFound());
	}

	private ResultActions history(String token, String roomId, String query) throws Exception {
		return mockMvc.perform(get("/api/rooms/" + roomId + "/messages" + query)
				.header("Authorization", TestUsers.bearer(token)));
	}

	private void saveMessages(String username, String roomId, int count) {
		UUID userId = jdbc.sql("SELECT id FROM users WHERE username = :u").param("u", username).query(UUID.class)
				.single();
		for (int i = 1; i <= count; i++) {
			messages.save(UUID.fromString(roomId), userId, UUID.randomUUID(), "m" + i);
		}
	}
}
