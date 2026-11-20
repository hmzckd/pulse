package com.hmzckd.pulse.message;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.hmzckd.pulse.IntegrationTest;
import com.hmzckd.pulse.TestApi;
import com.hmzckd.pulse.TestUsers;
import com.hmzckd.pulse.TestSocket;

import tools.jackson.databind.JsonNode;

@IntegrationTest
class MessagePersistenceTests {

	@LocalServerPort
	int port;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	MessageService messages;

	@Autowired
	JdbcClient jdbc;

	@Test
	void messagesInARoomAreNumberedOneTwoThree() throws Exception {
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String roomId = TestApi.createRoom(mockMvc, aliToken, "Numbers");

		try (var socket = TestSocket.connect(port, aliToken)) {
			for (int expected = 1; expected <= 3; expected++) {
				String clientMsgId = UUID.randomUUID().toString();
				socket.sendMessage(roomId, clientMsgId, "message " + expected);

				JsonNode ack = socket.next("message.ack");
				assertThat(ack.path("clientMsgId").asString()).isEqualTo(clientMsgId);
				assertThat(ack.path("seq").asLong()).isEqualTo(expected);
				JsonNode created = socket.next("message.new");
				assertThat(created.path("seq").asLong()).isEqualTo(expected);
				assertThat(created.path("id").asString()).isEqualTo(ack.path("id").asString());
			}
		}
		assertThat(lastSeq(roomId)).isEqualTo(3);
	}

	@Test
	void resendingTheSameClientMsgIdCreatesNoSecondMessage() throws Exception {
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String ayseToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ayse"));
		String roomId = TestApi.createRoom(mockMvc, aliToken, "Retry");
		TestApi.join(mockMvc, ayseToken, roomId);
		String clientMsgId = UUID.randomUUID().toString();

		try (var ali = TestSocket.connect(port, aliToken); var ayse = TestSocket.connect(port, ayseToken)) {
			ali.sendMessage(roomId, clientMsgId, "only once");
			long firstSeq = ali.next("message.ack").path("seq").asLong();
			ali.next("message.new");
			ayse.next("message.new");

			ali.sendMessage(roomId, clientMsgId, "only once");
			assertThat(ali.next("message.ack").path("seq").asLong()).isEqualTo(firstSeq);
			ali.expectNothing();
			ayse.expectNothing();
		}
		assertThat(messageCount(roomId)).isEqualTo(1);
	}

	@Test
	void concurrentSendersGetGaplessUniqueNumbers() throws Exception {
		UUID aliId = userId(TestUsers.uniqueName("ali"));
		UUID roomId = UUID.fromString(TestApi.createRoom(mockMvc, tokenFor(aliId), "Busy"));
		int senders = 40;

		List<Long> seqs = runConcurrently(senders,
				i -> messages.save(roomId, aliId, UUID.randomUUID(), "m" + i).message().seq());

		assertThat(seqs).containsExactlyInAnyOrderElementsOf(range(1, senders));
		assertThat(lastSeq(roomId.toString())).isEqualTo(senders);
	}

	@Test
	void concurrentDuplicatesBecomeOneMessageWithoutAGap() throws Exception {
		UUID aliId = userId(TestUsers.uniqueName("ali"));
		UUID roomId = UUID.fromString(TestApi.createRoom(mockMvc, tokenFor(aliId), "Dupes"));
		UUID clientMsgId = UUID.randomUUID();

		List<Long> seqs = runConcurrently(10, i -> messages.save(roomId, aliId, clientMsgId, "same").message().seq());

		assertThat(seqs).containsOnly(1L);
		assertThat(messageCount(roomId.toString())).isEqualTo(1);
		assertThat(lastSeq(roomId.toString())).isEqualTo(1);
	}

	private interface Task {
		long run(int index) throws Exception;
	}

	private static List<Long> runConcurrently(int count, Task task) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Long>> futures = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(count)) {
			for (int i = 0; i < count; i++) {
				int index = i;
				Callable<Long> call = () -> {
					start.await();
					return task.run(index);
				};
				futures.add(pool.submit(call));
			}
			start.countDown();
			List<Long> results = new ArrayList<>();
			for (Future<Long> future : futures) {
				results.add(future.get());
			}
			return results;
		}
	}

	private static List<Long> range(long from, long to) {
		List<Long> values = new ArrayList<>();
		for (long v = from; v <= to; v++) {
			values.add(v);
		}
		return values;
	}

	private final java.util.Map<UUID, String> tokens = new java.util.HashMap<>();

	private UUID userId(String username) throws Exception {
		String token = TestUsers.login(mockMvc, username);
		UUID id = jdbc.sql("SELECT id FROM users WHERE username = :u").param("u", username).query(UUID.class).single();
		tokens.put(id, token);
		return id;
	}

	private String tokenFor(UUID userId) {
		return tokens.get(userId);
	}

	private long lastSeq(String roomId) {
		return jdbc.sql("SELECT last_seq FROM rooms WHERE id = :id").param("id", UUID.fromString(roomId))
				.query(Long.class).single();
	}

	private int messageCount(String roomId) {
		return jdbc.sql("SELECT count(*) FROM messages WHERE room_id = :id").param("id", UUID.fromString(roomId))
				.query(Integer.class).single();
	}
}
