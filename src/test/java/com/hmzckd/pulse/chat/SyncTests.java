package com.hmzckd.pulse.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.hmzckd.pulse.IntegrationTest;
import com.hmzckd.pulse.TestApi;
import com.hmzckd.pulse.TestSocket;
import com.hmzckd.pulse.TestUsers;
import com.hmzckd.pulse.message.MessageService;

import tools.jackson.databind.JsonNode;

@IntegrationTest
class SyncTests {

	@LocalServerPort
	int port;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	MessageService messages;

	@Autowired
	JdbcClient jdbc;

	@Test
	void reconnectingClientGetsWhatItMissed() throws Exception {
		String ali = TestUsers.uniqueName("ali");
		String aliToken = TestUsers.login(mockMvc, ali);
		String ayseToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ayse"));
		String roomId = TestApi.createRoom(mockMvc, aliToken, "Genel");
		TestApi.join(mockMvc, ayseToken, roomId);

		long lastSeenByAyse;
		try (TestSocket aliSocket = TestSocket.connect(port, aliToken)) {
			try (TestSocket ayse = TestSocket.connect(port, ayseToken)) {
				aliSocket.sendMessage(roomId, "before the tunnel");
				lastSeenByAyse = ayse.next("message.new").path("seq").asLong();
			}
			aliSocket.next("message.ack");
			aliSocket.next("message.new");
			for (String text : List.of("one", "two", "three")) {
				aliSocket.sendMessage(roomId, text);
				aliSocket.next("message.ack");
				aliSocket.next("message.new");
			}

			try (TestSocket ayse = TestSocket.connect(port, ayseToken)) {
				ayse.send(sync(roomId, lastSeenByAyse));

				JsonNode result = ayse.next("sync.messages");
				assertThat(result.path("roomId").asString()).isEqualTo(roomId);
				assertThat(result.path("truncated").asBoolean()).isFalse();
				assertThat(texts(result)).containsExactly("one", "two", "three");
				assertThat(seqs(result)).containsExactly(lastSeenByAyse + 1, lastSeenByAyse + 2, lastSeenByAyse + 3);
				assertThat(result.path("messages").get(0).path("sender").asString()).isEqualTo(ali);
			}
		}
	}

	@Test
	void upToDateClientGetsAnEmptyList() throws Exception {
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String roomId = TestApi.createRoom(mockMvc, aliToken, "Quiet");

		try (TestSocket socket = TestSocket.connect(port, aliToken)) {
			socket.send(sync(roomId, 0));
			JsonNode result = socket.next("sync.messages");
			assertThat(result.path("messages").size()).isZero();
			assertThat(result.path("truncated").asBoolean()).isFalse();
		}
	}

	@Test
	void moreThan500MissedMessagesAreTruncated() throws Exception {
		String ali = TestUsers.uniqueName("ali");
		String aliToken = TestUsers.login(mockMvc, ali);
		String roomId = TestApi.createRoom(mockMvc, aliToken, "Flood");
		UUID aliId = jdbc.sql("SELECT id FROM users WHERE username = :u").param("u", ali).query(UUID.class).single();
		for (int i = 0; i < MessageService.SYNC_LIMIT + 20; i++) {
			messages.save(UUID.fromString(roomId), aliId, UUID.randomUUID(), "m" + i);
		}

		try (TestSocket socket = TestSocket.connect(port, aliToken)) {
			socket.send(sync(roomId, 0));
			JsonNode result = socket.next("sync.messages");
			assertThat(result.path("messages").size()).isEqualTo(MessageService.SYNC_LIMIT);
			assertThat(result.path("truncated").asBoolean()).isTrue();
			assertThat(result.path("messages").get(0).path("seq").asLong()).isEqualTo(1);
		}
	}

	@Test
	void messagesSentDuringReconnectAreNeverLost() throws Exception {
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String ayseToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ayse"));
		String roomId = TestApi.createRoom(mockMvc, aliToken, "Busy");
		TestApi.join(mockMvc, ayseToken, roomId);
		int total = 60;

		try (TestSocket aliSocket = TestSocket.connect(port, aliToken)) {
			CompletableFuture<Void> sending = CompletableFuture.runAsync(() -> {
				try {
					for (int i = 0; i < total; i++) {
						aliSocket.sendMessage(roomId, "m" + i);
						Thread.sleep(2);
					}
				}
				catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
			});

			Thread.sleep(40);
			Set<Long> seen = new TreeSet<>();
			try (TestSocket ayse = TestSocket.connect(port, ayseToken)) {
				ayse.send(sync(roomId, 0));
				sending.join();
				while (seen.size() < total) {
					JsonNode frame = ayse.next();
					switch (frame.path("type").asString()) {
						case "message.new" -> seen.add(frame.path("seq").asLong());
						case "sync.messages" -> seqs(frame).forEach(seen::add);
						default -> throw new AssertionError("unexpected " + frame);
					}
				}
			}
			assertThat(seen).hasSize(total).first().isEqualTo(1L);
			assertThat(seen).last().isEqualTo((long) total);
		}
	}

	@Test
	void syncForAForeignOrInvalidRoomIsRejected() throws Exception {
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String mehmetToken = TestUsers.login(mockMvc, TestUsers.uniqueName("mehmet"));
		String roomId = TestApi.createRoom(mockMvc, aliToken, "Mine");

		try (TestSocket mehmet = TestSocket.connect(port, mehmetToken)) {
			mehmet.send(sync(roomId, 0));
			assertThat(mehmet.next("error").path("code").asString()).isEqualTo("not_a_member");

			mehmet.send("{\"type\":\"sync\",\"rooms\":[1,2]}");
			assertThat(mehmet.next("error").path("code").asString()).isEqualTo("invalid_sync");

			mehmet.send("{\"type\":\"sync\",\"rooms\":{\"" + roomId + "\":-1}}");
			assertThat(mehmet.next("error").path("code").asString()).isEqualTo("invalid_sync");

			mehmet.send("{\"type\":\"sync\",\"rooms\":{\"" + roomId + "\":\"ten\"}}");
			assertThat(mehmet.next("error").path("code").asString()).isEqualTo("invalid_sync");
		}
	}

	private static String sync(String roomId, long lastSeen) {
		return "{\"type\":\"sync\",\"rooms\":{\"" + roomId + "\":" + lastSeen + "}}";
	}

	private static List<String> texts(JsonNode result) {
		List<String> texts = new ArrayList<>();
		result.path("messages").forEach(m -> texts.add(m.path("text").asString()));
		return texts;
	}

	private static List<Long> seqs(JsonNode result) {
		List<Long> seqs = new ArrayList<>();
		result.path("messages").forEach(m -> seqs.add(m.path("seq").asLong()));
		return seqs;
	}
}
