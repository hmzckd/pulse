package com.hmzckd.pulse;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class TestSocket implements AutoCloseable {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
	private final WebSocketSession session;

	private TestSocket(int port, String token) throws Exception {
		this(port, token, new WebSocketHttpHeaders());
	}

	private TestSocket(int port, String token, WebSocketHttpHeaders headers) throws Exception {
		URI uri = URI.create("ws://localhost:" + port + "/ws" + (token == null ? "" : "?token=" + token));
		TextWebSocketHandler handler = new TextWebSocketHandler() {
			@Override
			protected void handleTextMessage(WebSocketSession session, TextMessage message) {
				received.add(JSON.readTree(message.getPayload()));
			}
		};
		WebSocketContainer container = ContainerProvider.getWebSocketContainer();
		container.setDefaultMaxTextMessageBufferSize(1024 * 1024);
		this.session = new StandardWebSocketClient(container)
				.execute(handler, headers, uri)
				.get(5, TimeUnit.SECONDS);
	}

	public static TestSocket connect(int port, String token) throws Exception {
		TestSocket socket = new TestSocket(port, token);
		JsonNode ready = socket.next();
		assertThat(ready.path("type").asString()).isEqualTo("ready");
		return socket;
	}

	public static TestSocket connectWithoutReady(int port, String token) throws Exception {
		return new TestSocket(port, token);
	}

	public static TestSocket connectFromOrigin(int port, String token, String origin) throws Exception {
		WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
		headers.setOrigin(origin);
		return new TestSocket(port, token, headers);
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
