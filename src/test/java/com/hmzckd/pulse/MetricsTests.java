package com.hmzckd.pulse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.MockMvc;

import io.micrometer.core.instrument.MeterRegistry;

@IntegrationTest
class MetricsTests {

	@LocalServerPort
	int port;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	MeterRegistry meters;

	@Test
	void prometheusEndpointIsPublicAndListsOurMeters() throws Exception {
		mockMvc.perform(get("/actuator/prometheus"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("pulse_connections_open")))
				.andExpect(content().string(containsString("pulse_messages_sent_total")))
				.andExpect(content().string(containsString("pulse_connections_closed_total{reason=\"slow_consumer\"}")));
	}

	@Test
	void openConnectionsAndSentMessagesAreCounted() throws Exception {
		String token = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String roomId = TestApi.createRoom(mockMvc, token, "Genel");
		double openBefore = meters.get("pulse.connections.open").gauge().value();
		double sentBefore = meters.get("pulse.messages.sent").counter().count();

		try (TestSocket socket = TestSocket.connect(port, token)) {
			assertThat(meters.get("pulse.connections.open").gauge().value()).isEqualTo(openBefore + 1);
			String clientMsgId = java.util.UUID.randomUUID().toString();
			socket.sendMessage(roomId, clientMsgId, "bir");
			socket.next("message.ack");
			socket.sendMessage(roomId, clientMsgId, "bir");
			socket.next("message.new");
			socket.next("message.ack");
			assertThat(meters.get("pulse.messages.sent").counter().count()).isEqualTo(sentBefore + 1);
		}
		await().atMost(Duration.ofSeconds(5))
				.until(() -> meters.get("pulse.connections.open").gauge().value() == openBefore);
	}
}
