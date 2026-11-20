package com.hmzckd.pulse.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.hmzckd.pulse.IntegrationTest;
import com.hmzckd.pulse.TestUsers;
import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.JsonNode;

@IntegrationTest
class ChatWebSocketTests {

	@LocalServerPort
	int port;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ConnectionRegistry registry;

	@Test
	void connectionWithoutTokenIsRefused() {
		assertThatThrownBy(() -> TestSocket.connect(port, null)).hasMessageContaining("401");
	}

	@Test
	void connectionWithInvalidTokenIsRefused() {
		assertThatThrownBy(() -> TestSocket.connect(port, "not-a-jwt")).hasMessageContaining("401");
	}

	@Test
	void connectionFromAnotherWebsiteIsRefused() throws Exception {
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));

		assertThatThrownBy(() -> TestSocket.connectFromOrigin(port, aliToken, "http://evil.example"))
				.hasMessageContaining("403");
		TestSocket.connectFromOrigin(port, aliToken, "http://localhost:" + port).close();
	}

	@Test
	void messageReachesEveryMemberIncludingSender() throws Exception {
		String ali = TestUsers.uniqueName("ali");
		String aliToken = TestUsers.login(mockMvc, ali);
		String ayseToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ayse"));
		String roomId = createRoom(aliToken);
		join(ayseToken, roomId);

		try (TestSocket aliSocket = TestSocket.connect(port, aliToken);
				TestSocket ayseSocket = TestSocket.connect(port, ayseToken)) {
			aliSocket.send(send(roomId, "selam"));

			JsonNode atAyse = ayseSocket.next();
			assertThat(atAyse.path("type").asString()).isEqualTo("message.new");
			assertThat(atAyse.path("roomId").asString()).isEqualTo(roomId);
			assertThat(atAyse.path("sender").asString()).isEqualTo(ali);
			assertThat(atAyse.path("text").asString()).isEqualTo("selam");
			assertThat(atAyse.path("sentAt").asString()).isNotEmpty();
			assertThat(aliSocket.next().path("text").asString()).isEqualTo("selam");
		}
	}

	@Test
	void readyListsTheRoomsTheConnectionListensTo() throws Exception {
		String ali = TestUsers.uniqueName("ali");
		String aliToken = TestUsers.login(mockMvc, ali);
		String roomId = createRoom(aliToken);

		TestSocket raw = TestSocket.connectWithoutReady(port, aliToken);
		try (raw) {
			JsonNode ready = raw.next();
			assertThat(ready.path("type").asString()).isEqualTo("ready");
			assertThat(ready.path("username").asString()).isEqualTo(ali);
			assertThat(ready.path("rooms").get(0).asString()).isEqualTo(roomId);
		}
	}

	@Test
	void everyTabOfTheSameUserReceivesTheMessage() throws Exception {
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String roomId = createRoom(aliToken);

		try (TestSocket tab1 = TestSocket.connect(port, aliToken);
				TestSocket tab2 = TestSocket.connect(port, aliToken)) {
			tab1.send(send(roomId, "from tab 1"));

			assertThat(tab1.next().path("text").asString()).isEqualTo("from tab 1");
			assertThat(tab2.next().path("text").asString()).isEqualTo("from tab 1");
		}
	}

	@Test
	void nonMemberNeitherReceivesNorSends() throws Exception {
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String mehmetToken = TestUsers.login(mockMvc, TestUsers.uniqueName("mehmet"));
		String roomId = createRoom(aliToken);

		try (TestSocket aliSocket = TestSocket.connect(port, aliToken);
				TestSocket mehmetSocket = TestSocket.connect(port, mehmetToken)) {
			aliSocket.send(send(roomId, "members only"));
			aliSocket.next();
			mehmetSocket.expectNothing();

			mehmetSocket.send(send(roomId, "let me in"));
			JsonNode error = mehmetSocket.next();
			assertThat(error.path("type").asString()).isEqualTo("error");
			assertThat(error.path("code").asString()).isEqualTo("not_a_member");
			aliSocket.expectNothing();
		}
	}

	@Test
	void joiningWhileConnectedStartsDeliveryWithoutReconnecting() throws Exception {
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String ayseToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ayse"));
		String roomId = createRoom(aliToken);

		try (TestSocket aliSocket = TestSocket.connect(port, aliToken);
				TestSocket ayseSocket = TestSocket.connect(port, ayseToken)) {
			join(ayseToken, roomId);

			aliSocket.send(send(roomId, "welcome"));

			assertThat(ayseSocket.next().path("text").asString()).isEqualTo("welcome");
		}
	}

	@Test
	void badInputGetsAnErrorAndTheConnectionStaysOpen() throws Exception {
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String roomId = createRoom(aliToken);

		try (TestSocket socket = TestSocket.connect(port, aliToken)) {
			socket.send("{not json");
			assertThat(socket.next().path("code").asString()).isEqualTo("invalid_json");

			socket.send("{\"type\":\"dance\"}");
			assertThat(socket.next().path("code").asString()).isEqualTo("unknown_type");

			socket.send(send(roomId, "   "));
			assertThat(socket.next().path("code").asString()).isEqualTo("invalid_message");

			socket.send(send(roomId, "x".repeat(ChatWebSocketHandler.MAX_TEXT_LENGTH + 1)));
			assertThat(socket.next().path("code").asString()).isEqualTo("invalid_message");

			socket.send("{\"type\":\"message.send\",\"roomId\":\"nope\",\"text\":\"hi\"}");
			assertThat(socket.next().path("code").asString()).isEqualTo("invalid_message");

			assertThat(socket.isOpen()).isTrue();
			socket.send(send(roomId, "still here"));
			assertThat(socket.next().path("text").asString()).isEqualTo("still here");
		}
	}

	@Test
	void closedConnectionsAreForgotten() throws Exception {
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		int before = registry.count();

		TestSocket socket = TestSocket.connect(port, aliToken);
		await().atMost(Duration.ofSeconds(5)).until(() -> registry.count() == before + 1);

		socket.close();
		await().atMost(Duration.ofSeconds(5)).until(() -> registry.count() == before);
	}

	private String createRoom(String token) throws Exception {
		String body = mockMvc.perform(post("/api/rooms")
						.header("Authorization", TestUsers.bearer(token))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Chat\"}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.id");
	}

	private void join(String token, String roomId) throws Exception {
		mockMvc.perform(post("/api/rooms/" + roomId + "/members").header("Authorization", TestUsers.bearer(token)))
				.andExpect(status().isNoContent());
	}

	private static String send(String roomId, String text) {
		return "{\"type\":\"message.send\",\"roomId\":\"" + roomId + "\",\"text\":\"" + text + "\"}";
	}
}
