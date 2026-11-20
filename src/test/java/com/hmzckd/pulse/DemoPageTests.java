package com.hmzckd.pulse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.MockMvc;

import com.hmzckd.pulse.chat.ServerInstance;

@IntegrationTest
class DemoPageTests {

	@LocalServerPort
	int port;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ServerInstance instance;

	@Test
	void demoPageIsServedWithoutLogin() throws Exception {
		mockMvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
		mockMvc.perform(get("/index.html"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("<title>Pulse</title>")));
		mockMvc.perform(get("/app.js")).andExpect(status().isOk());
		mockMvc.perform(get("/app.css")).andExpect(status().isOk());
	}

	@Test
	void readyTellsWhichServerTheClientIsOn() throws Exception {
		String token = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));

		try (TestSocket socket = TestSocket.connectWithoutReady(port, token)) {
			assertThat(socket.next("ready").path("server").asString()).isEqualTo(instance.id());
		}
	}
}
