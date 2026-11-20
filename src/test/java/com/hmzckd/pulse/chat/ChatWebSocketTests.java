package com.hmzckd.pulse.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.MockMvc;

import com.hmzckd.pulse.IntegrationTest;
import com.hmzckd.pulse.TestApi;
import com.hmzckd.pulse.TestSocket;
import com.hmzckd.pulse.TestUsers;

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
	void readyListsTheRoomsTheConnectionListensTo() throws Exception {
		String ali = TestUsers.uniqueName("ali");
		String aliToken = TestUsers.login(mockMvc, ali);
		String roomId = TestApi.createRoom(mockMvc, aliToken, "Chat");

		try (TestSocket raw = TestSocket.connectWithoutReady(port, aliToken)) {
			JsonNode ready = raw.next("ready");
			assertThat(ready.path("username").asString()).isEqualTo(ali);
			assertThat(ready.path("rooms").get(0).asString()).isEqualTo(roomId);
		}
	}

	@Test
	void messageReachesEveryMemberIncludingSender() throws Exception {
		String ali = TestUsers.uniqueName("ali");
		String aliToken = TestUsers.login(mockMvc, ali);
		String ayseToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ayse"));
		String roomId = TestApi.createRoom(mockMvc, aliToken, "Chat");
		TestApi.join(mockMvc, ayseToken, roomId);

		try (TestSocket aliSocket = TestSocket.connect(port, aliToken);
				TestSocket ayseSocket = TestSocket.connect(port, ayseToken)) {
			aliSocket.sendMessage(roomId, "selam");

			JsonNode atAyse = ayseSocket.next("message.new");
			assertThat(atAyse.path("roomId").asString()).isEqualTo(roomId);
			assertThat(atAyse.path("sender").asString()).isEqualTo(ali);
			assertThat(atAyse.path("text").asString()).isEqualTo("selam");
			assertThat(atAyse.path("sentAt").asString()).isNotEmpty();
			aliSocket.next("message.ack");
			assertThat(aliSocket.next("message.new").path("text").asString()).isEqualTo("selam");
		}
	}

	@Test
	void everyTabOfTheSameUserReceivesTheMessage() throws Exception {
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String roomId = TestApi.createRoom(mockMvc, aliToken, "Chat");

		try (TestSocket tab1 = TestSocket.connect(port, aliToken);
				TestSocket tab2 = TestSocket.connect(port, aliToken)) {
			tab1.sendMessage(roomId, "from tab 1");

			tab1.next("message.ack");
			assertThat(tab1.next("message.new").path("text").asString()).isEqualTo("from tab 1");
			assertThat(tab2.next("message.new").path("text").asString()).isEqualTo("from tab 1");
		}
	}

	@Test
	void nonMemberNeitherReceivesNorSends() throws Exception {
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String mehmetToken = TestUsers.login(mockMvc, TestUsers.uniqueName("mehmet"));
		String roomId = TestApi.createRoom(mockMvc, aliToken, "Chat");

		try (TestSocket aliSocket = TestSocket.connect(port, aliToken);
				TestSocket mehmetSocket = TestSocket.connect(port, mehmetToken)) {
			aliSocket.sendMessage(roomId, "members only");
			aliSocket.next("message.ack");
			aliSocket.next("message.new");
			mehmetSocket.expectNothing();

			mehmetSocket.sendMessage(roomId, "let me in");
			assertThat(mehmetSocket.next("error").path("code").asString()).isEqualTo("not_a_member");
			aliSocket.expectNothing();
		}
	}

	@Test
	void joiningWhileConnectedStartsDeliveryWithoutReconnecting() throws Exception {
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String ayseToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ayse"));
		String roomId = TestApi.createRoom(mockMvc, aliToken, "Chat");

		try (TestSocket aliSocket = TestSocket.connect(port, aliToken);
				TestSocket ayseSocket = TestSocket.connect(port, ayseToken)) {
			TestApi.join(mockMvc, ayseToken, roomId);

			aliSocket.sendMessage(roomId, "welcome");

			assertThat(ayseSocket.next("message.new").path("text").asString()).isEqualTo("welcome");
		}
	}

	@Test
	void badInputGetsAnErrorAndTheConnectionStaysOpen() throws Exception {
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String roomId = TestApi.createRoom(mockMvc, aliToken, "Chat");

		try (TestSocket socket = TestSocket.connect(port, aliToken)) {
			socket.send("{not json");
			assertThat(socket.next("error").path("code").asString()).isEqualTo("invalid_json");

			socket.send("{\"type\":\"dance\"}");
			assertThat(socket.next("error").path("code").asString()).isEqualTo("unknown_type");

			socket.sendMessage(roomId, "   ");
			assertThat(socket.next("error").path("code").asString()).isEqualTo("invalid_message");

			socket.sendMessage(roomId, "x".repeat(ChatWebSocketHandler.MAX_TEXT_LENGTH + 1));
			assertThat(socket.next("error").path("code").asString()).isEqualTo("invalid_message");

			socket.send("{\"type\":\"message.send\",\"roomId\":\"nope\",\"text\":\"hi\"}");
			assertThat(socket.next("error").path("code").asString()).isEqualTo("invalid_message");

			socket.send("{\"type\":\"message.send\",\"roomId\":\"" + roomId + "\",\"text\":\"no client id\"}");
			assertThat(socket.next("error").path("code").asString()).isEqualTo("invalid_message");

			assertThat(socket.isOpen()).isTrue();
			socket.sendMessage(roomId, "still here");
			socket.next("message.ack");
			assertThat(socket.next("message.new").path("text").asString()).isEqualTo("still here");
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
}
