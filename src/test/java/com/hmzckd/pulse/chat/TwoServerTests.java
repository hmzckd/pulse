package com.hmzckd.pulse.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.hmzckd.pulse.IntegrationTest;
import com.hmzckd.pulse.PulseApplication;
import com.hmzckd.pulse.TestApi;
import com.hmzckd.pulse.TestSocket;
import com.hmzckd.pulse.TestUsers;
import com.redis.testcontainers.RedisContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TwoServerTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@LocalServerPort
	int portA;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	RedisContainer redisContainer;

	@Autowired
	StringRedisTemplate redis;

	ConfigurableApplicationContext serverB;

	int portB;

	@BeforeAll
	void startSecondServer() {
		serverB = new SpringApplicationBuilder(PulseApplication.class).run(
				"--server.port=0",
				"--spring.datasource.url=" + postgres.getJdbcUrl(),
				"--spring.datasource.username=" + postgres.getUsername(),
				"--spring.datasource.password=" + postgres.getPassword(),
				"--spring.data.redis.host=" + redisContainer.getHost(),
				"--spring.data.redis.port=" + redisContainer.getFirstMappedPort(),
				"--" + IntegrationTest.JWT_SECRET_PROPERTY);
		portB = Integer.parseInt(serverB.getEnvironment().getRequiredProperty("local.server.port"));
	}

	@AfterAll
	void stopSecondServer() {
		if (serverB != null) {
			serverB.close();
		}
	}

	@Test
	void messageSentOnServerAReachesUserOnServerB() throws Exception {
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String ayseToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ayse"));
		String roomId = TestApi.createRoom(mockMvc, aliToken, "Genel");
		TestApi.join(mockMvc, ayseToken, roomId);

		try (TestSocket ali = TestSocket.connect(portA, aliToken);
				TestSocket ayse = TestSocket.connect(portB, ayseToken)) {
			ali.sendMessage(roomId, "merhaba B");

			JsonNode ack = ali.next("message.ack");
			JsonNode delivered = ayse.next("message.new");
			assertThat(delivered.path("text").asString()).isEqualTo("merhaba B");
			assertThat(delivered.path("seq").asLong()).isEqualTo(ack.path("seq").asLong());
			assertThat(ali.next("message.new").path("seq").asLong()).isEqualTo(ack.path("seq").asLong());

			ayse.sendMessage(roomId, "merhaba A");
			assertThat(ali.next("message.new").path("text").asString()).isEqualTo("merhaba A");
		}
	}

	@Test
	void typingAndReadMarkersCrossServers() throws Exception {
		String ali = TestUsers.uniqueName("ali");
		String aliToken = TestUsers.login(mockMvc, ali);
		String ayseToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ayse"));
		String roomId = TestApi.createRoom(mockMvc, aliToken, "Genel");
		TestApi.join(mockMvc, ayseToken, roomId);

		try (TestSocket aliOnA = TestSocket.connect(portA, aliToken);
				TestSocket aliOnB = TestSocket.connect(portB, aliToken);
				TestSocket ayseOnB = TestSocket.connect(portB, ayseToken)) {
			aliOnA.send("{\"type\":\"typing\",\"roomId\":\"" + roomId + "\"}");
			assertThat(ayseOnB.next("typing").path("username").asString()).isEqualTo(ali);
			aliOnB.expectNothing();

			ayseOnB.sendMessage(roomId, "okudun mu?");
			long seq = aliOnA.next("message.new").path("seq").asLong();
			aliOnB.next("message.new");

			aliOnA.send("{\"type\":\"read\",\"roomId\":\"" + roomId + "\",\"seq\":" + seq + "}");
			assertThat(aliOnA.next("read").path("seq").asLong()).isEqualTo(seq);
			assertThat(aliOnB.next("read").path("seq").asLong()).isEqualTo(seq);
		}
	}

	@Test
	void presenceIsSharedBetweenServers() throws Exception {
		String ali = TestUsers.uniqueName("ali");
		String ayse = TestUsers.uniqueName("ayse");
		String aliToken = TestUsers.login(mockMvc, ali);
		String ayseToken = TestUsers.login(mockMvc, ayse);
		String roomId = TestApi.createRoom(mockMvc, aliToken, "Genel");
		TestApi.join(mockMvc, ayseToken, roomId);

		try (TestSocket aliOnA = TestSocket.connectSeeingPresence(portA, aliToken)) {
			TestSocket ayseOnB = TestSocket.connectWithoutReady(portB, ayseToken);
			JsonNode ready = ayseOnB.next("ready");
			assertThat(ready.path("online").get(0).asString()).isEqualTo(ali);

			JsonNode online = aliOnA.next("presence");
			assertThat(online.path("username").asString()).isEqualTo(ayse);
			assertThat(online.path("online").asBoolean()).isTrue();

			TestSocket ayseOnA = TestSocket.connect(portA, ayseToken);
			ayseOnB.close();
			aliOnA.expectNothing();

			ayseOnA.close();
			JsonNode offline = aliOnA.next("presence");
			assertThat(offline.path("username").asString()).isEqualTo(ayse);
			assertThat(offline.path("online").asBoolean()).isFalse();
		}
	}

	@Test
	void joiningOnServerASubscribesTheUsersSocketsOnServerB() throws Exception {
		String ayse = TestUsers.uniqueName("ayse");
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String ayseToken = TestUsers.login(mockMvc, ayse);
		String roomId = TestApi.createRoom(mockMvc, aliToken, "Genel");

		try (TestSocket aliOnA = TestSocket.connectSeeingPresence(portA, aliToken);
				TestSocket ayseOnB = TestSocket.connect(portB, ayseToken)) {
			TestApi.join(mockMvc, ayseToken, roomId);
			assertThat(aliOnA.next("presence").path("username").asString()).isEqualTo(ayse);

			aliOnA.sendMessage(roomId, "hoş geldin");
			assertThat(ayseOnB.next("message.new").path("text").asString()).isEqualTo("hoş geldin");
		}
	}

	@Test
	void aServerListensToARoomOnlyWhileItHasConnectionsInIt() throws Exception {
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String ayseToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ayse"));
		String roomId = TestApi.createRoom(mockMvc, aliToken, "Genel");
		TestApi.join(mockMvc, ayseToken, roomId);
		String channel = Relay.roomChannel(UUID.fromString(roomId));

		assertThat(listeners(channel)).isZero();
		TestSocket ali = TestSocket.connect(portA, aliToken);
		TestSocket ayse = TestSocket.connect(portB, ayseToken);
		assertThat(listeners(channel)).isEqualTo(2);

		ayse.close();
		await().atMost(Duration.ofSeconds(5)).until(() -> listeners(channel) == 1);
		ali.close();
		await().atMost(Duration.ofSeconds(5)).until(() -> listeners(channel) == 0);
	}

	@Test
	void presenceIsAKeyWithAThirtySecondLifetime() throws Exception {
		String aliToken = TestUsers.login(mockMvc, TestUsers.uniqueName("ali"));
		String key = Presence.key(userIdOf(aliToken));

		try (TestSocket ali = TestSocket.connect(portA, aliToken)) {
			assertThat(redis.getExpire(key)).isBetween(1L, Presence.TTL.toSeconds());
		}
		await().atMost(Duration.ofSeconds(5)).until(() -> !redis.hasKey(key));
	}

	private static UUID userIdOf(String token) {
		String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
		return UUID.fromString(JSON.readTree(payload).path("uid").asString());
	}

	private long listeners(String channel) throws Exception {
		String output = redisContainer.execInContainer("redis-cli", "PUBSUB", "NUMSUB", channel).getStdout();
		return Long.parseLong(output.strip().lines().toList().get(1).strip());
	}
}
