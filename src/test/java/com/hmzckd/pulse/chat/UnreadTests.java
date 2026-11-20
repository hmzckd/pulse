package com.hmzckd.pulse.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.MockMvc;

import com.hmzckd.pulse.IntegrationTest;
import com.hmzckd.pulse.TestApi;
import com.hmzckd.pulse.TestSocket;
import com.hmzckd.pulse.TestUsers;
import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.JsonNode;

@IntegrationTest
class UnreadTests {

	@LocalServerPort
	int port;

	@Autowired
	MockMvc mockMvc;

	@Test
	void unreadIsLastSeqMinusLastReadAndOwnMessagesDoNotCount() throws Exception {
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String ayseToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ayse"));
		String roomId = TestApi.createRoom(mockMvc, aliToken, "Genel");
		TestApi.join(mockMvc, ayseToken, roomId);

		sendMessages(aliToken, roomId, 5);

		assertThat(unread(ayseToken, roomId)).isEqualTo(5);
		assertThat(unread(aliToken, roomId)).isZero();
	}

	@Test
	void readMovesOnlyForwardAndIsCappedAtTheLatestMessage() throws Exception {
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String ayseToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ayse"));
		String roomId = TestApi.createRoom(mockMvc, aliToken, "Genel");
		TestApi.join(mockMvc, ayseToken, roomId);
		sendMessages(aliToken, roomId, 5);

		try (TestSocket ayse = TestSocket.connect(port, ayseToken)) {
			ayse.send(read(roomId, 3));
			assertThat(ayse.next("read").path("seq").asLong()).isEqualTo(3);
			assertThat(unread(ayseToken, roomId)).isEqualTo(2);

			ayse.send(read(roomId, 1));
			assertThat(ayse.next("read").path("seq").asLong()).isEqualTo(3);
			assertThat(unread(ayseToken, roomId)).isEqualTo(2);

			ayse.send(read(roomId, 999));
			assertThat(ayse.next("read").path("seq").asLong()).isEqualTo(5);
			assertThat(unread(ayseToken, roomId)).isZero();
		}
	}

	@Test
	void readingInOneTabClearsTheCounterInTheOthers() throws Exception {
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String ayseToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ayse"));
		String roomId = TestApi.createRoom(mockMvc, aliToken, "Genel");
		TestApi.join(mockMvc, ayseToken, roomId);
		sendMessages(aliToken, roomId, 2);

		try (TestSocket phone = TestSocket.connect(port, ayseToken);
				TestSocket laptop = TestSocket.connect(port, ayseToken);
				TestSocket ali = TestSocket.connect(port, aliToken)) {
			phone.send(read(roomId, 2));

			JsonNode onLaptop = laptop.next("read");
			assertThat(onLaptop.path("roomId").asString()).isEqualTo(roomId);
			assertThat(onLaptop.path("seq").asLong()).isEqualTo(2);
			phone.next("read");
			ali.expectNothing();
		}
	}

	@Test
	void newMembersStartWithNothingUnread() throws Exception {
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String ayseToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ayse"));
		String roomId = TestApi.createRoom(mockMvc, aliToken, "Genel");
		sendMessages(aliToken, roomId, 4);

		TestApi.join(mockMvc, ayseToken, roomId);

		assertThat(unread(ayseToken, roomId)).isZero();
	}

	@Test
	void badReadIsRejected() throws Exception {
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String mehmetToken = TestUsers.login(mockMvc, TestUsers.uniqueName("mehmet"));
		String roomId = TestApi.createRoom(mockMvc, aliToken, "Genel");

		try (TestSocket mehmet = TestSocket.connect(port, mehmetToken)) {
			mehmet.send(read(roomId, 1));
			assertThat(mehmet.next("error").path("code").asString()).isEqualTo("not_a_member");
			mehmet.send("{\"type\":\"read\",\"roomId\":\"" + roomId + "\",\"seq\":-2}");
			assertThat(mehmet.next("error").path("code").asString()).isEqualTo("invalid_message");
		}
	}

	private void sendMessages(String token, String roomId, int count) throws Exception {
		try (TestSocket socket = TestSocket.connect(port, token)) {
			for (int i = 0; i < count; i++) {
				socket.sendMessage(roomId, "m" + i);
				socket.next("message.ack");
				socket.next("message.new");
			}
		}
	}

	private long unread(String token, String roomId) throws Exception {
		String body = mockMvc.perform(get("/api/rooms/mine").header("Authorization", TestUsers.bearer(token)))
				.andReturn().getResponse().getContentAsString();
		Number unread = JsonPath.<java.util.List<Number>>read(body, "$[?(@.id == '" + roomId + "')].unread").getFirst();
		return unread.longValue();
	}

	private static String read(String roomId, long seq) {
		return "{\"type\":\"read\",\"roomId\":\"" + roomId + "\",\"seq\":" + seq + "}";
	}
}
