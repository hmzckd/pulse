package com.hmzckd.pulse.chat;

import static org.assertj.core.api.Assertions.assertThat;

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
class TypingAndPresenceTests {

	@LocalServerPort
	int port;

	@Autowired
	MockMvc mockMvc;

	@Test
	void typingReachesOthersButNotTheTypistsOwnTabs() throws Exception {
		String ali = TestUsers.uniqueName("ali");
		String aliToken = TestUsers.login(mockMvc, ali);
		String ayseToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ayse"));
		String roomId = TestApi.createRoom(mockMvc, aliToken, "Genel");
		TestApi.join(mockMvc, ayseToken, roomId);

		try (TestSocket aliTab1 = TestSocket.connect(port, aliToken);
				TestSocket aliTab2 = TestSocket.connect(port, aliToken);
				TestSocket ayse = TestSocket.connect(port, ayseToken)) {
			aliTab1.send(typing(roomId));

			JsonNode event = ayse.next("typing");
			assertThat(event.path("roomId").asString()).isEqualTo(roomId);
			assertThat(event.path("username").asString()).isEqualTo(ali);
			aliTab1.expectNothing();
			aliTab2.expectNothing();
		}
	}

	@Test
	void typingIsThrottledToOneEventEveryTwoSeconds() throws Exception {
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String ayseToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ayse"));
		String roomId = TestApi.createRoom(mockMvc, aliToken, "Genel");
		TestApi.join(mockMvc, ayseToken, roomId);

		try (TestSocket aliSocket = TestSocket.connect(port, aliToken);
				TestSocket ayse = TestSocket.connect(port, ayseToken)) {
			for (int i = 0; i < 5; i++) {
				aliSocket.send(typing(roomId));
			}

			ayse.next("typing");
			ayse.expectNothing();
		}
	}

	@Test
	void typingInAForeignRoomIsRejected() throws Exception {
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String mehmetToken = TestUsers.login(mockMvc, TestUsers.uniqueName("mehmet"));
		String roomId = TestApi.createRoom(mockMvc, aliToken, "Genel");

		try (TestSocket mehmet = TestSocket.connect(port, mehmetToken)) {
			mehmet.send(typing(roomId));
			assertThat(mehmet.next("error").path("code").asString()).isEqualTo("not_a_member");
		}
	}

	@Test
	void userIsOnlineWhileAtLeastOneTabIsOpen() throws Exception {
		String ayseName = TestUsers.uniqueName("ayse");
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String ayseToken = TestUsers.login(mockMvc, ayseName);
		String roomId = TestApi.createRoom(mockMvc, aliToken, "Genel");
		TestApi.join(mockMvc, ayseToken, roomId);

		try (TestSocket ali = TestSocket.connectSeeingPresence(port, aliToken)) {
			TestSocket ayseTab1 = TestSocket.connect(port, ayseToken);
			JsonNode online = ali.next("presence");
			assertThat(online.path("username").asString()).isEqualTo(ayseName);
			assertThat(online.path("online").asBoolean()).isTrue();

			TestSocket ayseTab2 = TestSocket.connect(port, ayseToken);
			ayseTab1.close();
			ali.expectNothing();

			ayseTab2.close();
			JsonNode offline = ali.next("presence");
			assertThat(offline.path("username").asString()).isEqualTo(ayseName);
			assertThat(offline.path("online").asBoolean()).isFalse();
		}
	}

	@Test
	void readyListsWhoIsAlreadyOnline() throws Exception {
		String ali = TestUsers.uniqueName("ali");
		String aliToken = TestUsers.login(mockMvc, ali);
		String ayseToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ayse"));
		String mehmetToken = TestUsers.login(mockMvc, TestUsers.uniqueName("mehmet"));
		String roomId = TestApi.createRoom(mockMvc, aliToken, "Genel");
		TestApi.join(mockMvc, ayseToken, roomId);

		try (TestSocket aliSocket = TestSocket.connect(port, aliToken);
				TestSocket mehmet = TestSocket.connect(port, mehmetToken);
				TestSocket ayse = TestSocket.connectWithoutReady(port, ayseToken)) {
			JsonNode ready = ayse.next("ready");
			assertThat(ready.path("online").size()).isEqualTo(1);
			assertThat(ready.path("online").get(0).asString()).isEqualTo(ali);
		}
	}

	@Test
	void joiningARoomWhileOnlineAnnouncesPresenceThere() throws Exception {
		String ayseName = TestUsers.uniqueName("ayse");
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String ayseToken = TestUsers.login(mockMvc, ayseName);
		String roomId = TestApi.createRoom(mockMvc, aliToken, "Genel");

		try (TestSocket ali = TestSocket.connectSeeingPresence(port, aliToken);
				TestSocket ayse = TestSocket.connect(port, ayseToken)) {
			ali.expectNothing();

			TestApi.join(mockMvc, ayseToken, roomId);

			JsonNode online = ali.next("presence");
			assertThat(online.path("username").asString()).isEqualTo(ayseName);
			assertThat(online.path("online").asBoolean()).isTrue();
		}
	}

	private static String typing(String roomId) {
		return "{\"type\":\"typing\",\"roomId\":\"" + roomId + "\"}";
	}
}
