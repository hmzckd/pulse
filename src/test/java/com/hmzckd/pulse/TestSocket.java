package com.hmzckd.pulse;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class TestSocket implements AutoCloseable {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Set<String> IGNORED_BY_DEFAULT = Set.of("presence");

	private final BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
	private final WebSocketSession session;

	private TestSocket(String url, WebSocketHttpHeaders headers, Set<String> ignoredTypes) throws Exception {
		TextWebSocketHandler handler = new TextWebSocketHandler() {
			@Override
			protected void handleTextMessage(WebSocketSession session, TextMessage message) {
				JsonNode node = JSON.readTree(message.getPayload());
				if (!ignoredTypes.contains(node.path("type").asString())) {
					received.add(node);
				}
			}
		};
		WebSocketContainer container = ContainerProvider.getWebSocketContainer();
		container.setDefaultMaxTextMessageBufferSize(1024 * 1024);
		this.session = new StandardWebSocketClient(container)
				.execute(handler, headers, URI.create(url))
				.get(5, TimeUnit.SECONDS);
	}

	private static String url(int port, String token) {
		return "ws://localhost:" + port + "/ws" + (token == null ? "" : "?token=" + token);
	}

	public static TestSocket connect(int port, String token) throws Exception {
		return ready(new TestSocket(url(port, token), new WebSocketHttpHeaders(), IGNORED_BY_DEFAULT));
	}

	public static TestSocket connectSeeingPresence(int port, String token) throws Exception {
		return ready(new TestSocket(url(port, token), new WebSocketHttpHeaders(), Set.of()));
	}

	public static TestSocket connectWithoutReady(int port, String token) throws Exception {
		return new TestSocket(url(port, token), new WebSocketHttpHeaders(), IGNORED_BY_DEFAULT);
	}

	public static TestSocket connectFromOrigin(int port, String token, String origin) throws Exception {
		WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
		headers.setOrigin(origin);
		return new TestSocket(url(port, token), headers, IGNORED_BY_DEFAULT);
	}

	private static TestSocket ready(TestSocket socket) throws InterruptedException {
		socket.next("ready");
		return socket;
	}

	public void sendMessage(String roomId, String text) throws Exception {
		sendMessage(roomId, UUID.randomUUID().toString(), text);
	}

	public void sendMessage(String roomId, String clientMsgId, String text) throws Exception {
		send("{\"type\":\"message.send\",\"roomId\":\"" + roomId + "\",\"clientMsgId\":\"" + clientMsgId
				+ "\",\"text\":\"" + text + "\"}");
	}

	public void send(String raw) throws Exception {
		session.sendMessage(new TextMessage(raw));
	}

	public JsonNode next() throws InterruptedException {
		JsonNode node = received.poll(5, TimeUnit.SECONDS);
		assertThat(node).as("expected a message within 5 seconds").isNotNull();
		return node;
	}

	public JsonNode next(String type) throws InterruptedException {
		JsonNode node = next();
		assertThat(node.path("type").asString()).as("type of " + node).isEqualTo(type);
		return node;
	}

	public void expectNothing() throws InterruptedException {
		JsonNode node = received.poll(300, TimeUnit.MILLISECONDS);
		assertThat(node).as("expected no message").isNull();
	}

	public boolean isOpen() {
		return session.isOpen();
	}

	@Override
	public void close() throws Exception {
		if (session.isOpen()) {
			session.close(CloseStatus.NORMAL);
		}
	}
}
