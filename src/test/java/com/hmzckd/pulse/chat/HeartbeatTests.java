package com.hmzckd.pulse.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.hmzckd.pulse.IntegrationTest;
import com.hmzckd.pulse.RawSocket;
import com.hmzckd.pulse.TestSocket;
import com.hmzckd.pulse.TestUsers;

@IntegrationTest
@TestPropertySource(properties = { "pulse.ws.ping-interval=200ms", "pulse.ws.pong-timeout=1s" })
class HeartbeatTests {

	@LocalServerPort
	int port;

	@Autowired
	MockMvc mockMvc;

	@Test
	void clientThatAnswersPingsStaysConnected() throws Exception {
		String token = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));

		try (TestSocket ali = TestSocket.connect(port, token)) {
			Thread.sleep(2_000);
			assertThat(ali.isOpen()).isTrue();
		}
	}

	@Test
	void clientThatNeverAnswersPingsIsClosed() throws Exception {
		String token = TestUsers.login(mockMvc, TestUsers.uniqueName("ayse"));

		try (RawSocket ayse = RawSocket.connect(port, token)) {
			assertThat(ayse.awaitCloseCode(Duration.ofSeconds(5))).isEqualTo(4000);
		}
	}
}
