package com.hmzckd.pulse;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@IntegrationTest
@Import(PulseApplicationTests.ThreadProbe.class)
class PulseApplicationTests {

	@LocalServerPort
	int port;

	@Autowired
	MockMvc mockMvc;

	private final HttpClient http = HttpClient.newHttpClient();

	@Test
	void healthIsUp() throws Exception {
		HttpResponse<String> response = get("/actuator/health", null);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"status\":\"UP\"");
	}

	@Test
	void requestsRunOnVirtualThreads() throws Exception {
		String token = TestUsers.login(mockMvc, TestUsers.uniqueName("probe"));

		HttpResponse<String> response = get("/test/thread", token);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).isEqualTo("virtual=true");
	}

	private HttpResponse<String> get(String path, String token) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
		if (token != null) {
			request.header("Authorization", TestUsers.bearer(token));
		}
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	@TestConfiguration
	@RestController
	static class ThreadProbe {

		@GetMapping("/test/thread")
		String thread() {
			return "virtual=" + Thread.currentThread().isVirtual();
		}
	}
}
