package com.hmzckd.pulse.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.MockMvc;

import io.micrometer.core.instrument.MeterRegistry;

import com.hmzckd.pulse.IntegrationTest;
import com.hmzckd.pulse.RawSocket;
import com.hmzckd.pulse.TestApi;
import com.hmzckd.pulse.TestSocket;
import com.hmzckd.pulse.TestUsers;

@IntegrationTest
class ProtectionTests {

	@LocalServerPort
	int port;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ConnectionRegistry registry;

	@Autowired
	MeterRegistry meters;

	@Test
	void moreThanTwentyMessagesInTenSecondsAreRateLimited() throws Exception {
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String roomId = TestApi.createRoom(mockMvc, aliToken, "Genel");

		double limitedBefore = meters.get("pulse.messages.rate.limited").counter().count();

		try (TestSocket ali = TestSocket.connect(port, aliToken)) {
			for (int i = 0; i < 30; i++) {
				ali.sendMessage(roomId, "m" + i);
			}

			Map<String, Integer> counts = new HashMap<>();
			for (int i = 0; i < 50; i++) {
				var frame = ali.next();
				String key = frame.path("type").asString() + " " + frame.path("code").asString("");
				counts.merge(key.strip(), 1, Integer::sum);
			}
			assertThat(counts).containsEntry("message.ack", 20)
					.containsEntry("message.new", 20)
					.containsEntry("error rate_limited", 10);
			assertThat(ali.isOpen()).isTrue();
		}
		assertThat(meters.get("pulse.messages.rate.limited").counter().count()).isEqualTo(limitedBefore + 10);
	}

	@Test
	void slowConsumerIsClosedWith4008() throws Exception {
		String ayseName = TestUsers.uniqueName("ayse");
		String ayseToken = TestUsers.login(mockMvc, ayseName);
		String frame = "{\"type\":\"message.new\",\"text\":\"" + "x".repeat(64 * 1024) + "\"}";
		double closedBefore = slowConsumerCloses();

		try (RawSocket ayse = RawSocket.connect(port, ayseToken)) {
			await().atMost(Duration.ofSeconds(5)).until(() -> connectionOf(ayseName) != null);
			Connection connection = connectionOf(ayseName);

			try (ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor()) {
				for (int i = 0; i < 400; i++) {
					senders.submit(() -> connection.send(frame));
				}
				await().atMost(Duration.ofSeconds(10)).until(() -> connectionOf(ayseName) == null);
			}
			assertThat(ayse.awaitCloseCode(Duration.ofSeconds(10))).isEqualTo(4008);
		}
		assertThat(slowConsumerCloses()).isEqualTo(closedBefore + 1);
	}

	private double slowConsumerCloses() {
		return meters.get("pulse.connections.closed").tag("reason", "slow_consumer").counter().count();
	}

	private Connection connectionOf(String username) {
		return registry.all().stream()
				.filter(connection -> connection.user().username().equals(username))
				.findFirst()
				.orElse(null);
	}
}
