package com.hmzckd.pulse.chat;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hmzckd.pulse.auth.CurrentUser;
import com.hmzckd.pulse.message.MessageService;
import com.hmzckd.pulse.room.RoomService;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class ChatWebSocketHandler extends TextWebSocketHandler {

	private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
	static final int MAX_TEXT_LENGTH = MessageService.MAX_TEXT_LENGTH;
	static final int MAX_SYNC_ROOMS = 100;
	private static final int SEND_TIME_LIMIT_MS = 5_000;
	private static final int BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;

	static final Duration TYPING_INTERVAL = Duration.ofSeconds(2);

	private final ConnectionRegistry registry;
	private final Relay relay;
	private final Presence presence;
	private final RoomService rooms;
	private final MessageService messages;
	private final JsonMapper json;
	private final ChatMetrics metrics;
	private final String instanceId;

	ChatWebSocketHandler(ConnectionRegistry registry, Relay relay, Presence presence, RoomService rooms,
			MessageService messages, JsonMapper json, ChatMetrics metrics, ServerInstance instance) {
		this.registry = registry;
		this.relay = relay;
		this.presence = presence;
		this.rooms = rooms;
		this.messages = messages;
		this.json = json;
		this.metrics = metrics;
		this.instanceId = instance.id();
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		CurrentUser user = (CurrentUser) session.getAttributes().get(TokenHandshakeInterceptor.USER_ATTRIBUTE);
		ConcurrentWebSocketSessionDecorator safe = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS,
				BUFFER_SIZE_LIMIT_BYTES);
		Connection connection = new Connection(safe, user, metrics, System.nanoTime());
		boolean firstForUser = registry.add(connection);
		List<UUID> roomIds = rooms.roomIdsOf(user.id());
		for (UUID roomId : roomIds) {
			registry.subscribe(connection, roomId);
		}
		relay.refresh(connection);
		if (firstForUser) {
			presence.cameOnline(user, roomIds);
		}
		connection.sendReady(json.writeValueAsString(ServerEvents.Ready.of(user.username(), instanceId, roomIds,
				presence.onlineAmong(rooms.roommatesOf(user.id())))));
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) {
		Connection connection = registry.get(session.getId());
		if (connection == null) {
			return;
		}
		JsonNode node;
		try {
			node = json.readTree(message.getPayload());
		}
		catch (JacksonException ex) {
			sendError(connection, "invalid_json", "message is not valid JSON");
			return;
		}
		String type = node.path("type").asString("");
		switch (type) {
			case "message.send" -> onMessageSend(connection, node);
			case "sync" -> onSync(connection, node);
			case "typing" -> onTyping(connection, node);
			case "read" -> onRead(connection, node);
			default -> sendError(connection, "unknown_type", "unknown message type: " + type);
		}
	}

	@Override
	protected void handlePongMessage(WebSocketSession session, PongMessage message) {
		Connection connection = registry.get(session.getId());
		if (connection != null) {
			connection.pongReceived(System.nanoTime());
		}
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		ConnectionRegistry.Removed removed = registry.remove(session.getId());
		if (removed == null) {
			return;
		}
		Connection connection = removed.connection();
		relay.refresh(connection);
		if (removed.lastForUser()) {
			presence.wentOffline(connection.user(), connection.rooms());
		}
	}

	private void onMessageSend(Connection connection, JsonNode node) {
		UUID roomId = parseUuid(node.path("roomId").asString(""));
		UUID clientMsgId = parseUuid(node.path("clientMsgId").asString(""));
		String text = node.path("text").asString("");
		if (roomId == null) {
			sendError(connection, "invalid_message", "roomId must be a UUID");
			return;
		}
		if (clientMsgId == null) {
			sendError(connection, "invalid_message", "clientMsgId must be a UUID");
			return;
		}
		if (text.isBlank() || text.length() > MAX_TEXT_LENGTH) {
			sendError(connection, "invalid_message", "text must be 1-" + MAX_TEXT_LENGTH + " characters");
			return;
		}
		if (!connection.isIn(roomId)) {
			sendError(connection, "not_a_member", "join the room before sending messages");
			return;
		}
		if (!connection.maySendMessage(System.nanoTime())) {
			metrics.rateLimited();
			sendError(connection, "rate_limited", "at most " + Connection.MESSAGE_LIMIT + " messages every "
					+ Connection.MESSAGE_WINDOW.toSeconds() + " seconds, try again with the same clientMsgId");
			return;
		}
		MessageService.SaveResult result;
		try {
			result = messages.save(roomId, connection.user().id(), clientMsgId, text);
		}
		catch (RuntimeException ex) {
			log.error("Could not save message {} in room {}", clientMsgId, roomId, ex);
			sendError(connection, "save_failed", "message was not saved, try again with the same clientMsgId");
			return;
		}
		connection.send(json.writeValueAsString(ServerEvents.MessageAck.of(result.message())));
		if (result.duplicate()) {
			return;
		}
		metrics.messageSent();
		relay.toRoom(roomId, null, json.writeValueAsString(ServerEvents.MessageNew.of(result.message())));
	}

	private void onTyping(Connection connection, JsonNode node) {
		UUID roomId = parseUuid(node.path("roomId").asString(""));
		if (roomId == null) {
			sendError(connection, "invalid_message", "roomId must be a UUID");
			return;
		}
		if (!connection.isIn(roomId)) {
			sendError(connection, "not_a_member", "not a member of room " + roomId);
			return;
		}
		if (!connection.mayAnnounceTyping(roomId, System.nanoTime(), TYPING_INTERVAL.toNanos())) {
			return;
		}
		relay.toRoom(roomId, connection.user().id(),
				json.writeValueAsString(ServerEvents.Typing.of(roomId, connection.user().username())));
	}

	private void onRead(Connection connection, JsonNode node) {
		UUID roomId = parseUuid(node.path("roomId").asString(""));
		JsonNode seq = node.path("seq");
		if (roomId == null || !seq.isIntegralNumber() || seq.asLong() < 0) {
			sendError(connection, "invalid_message", "read needs a roomId and a seq of 0 or more");
			return;
		}
		if (!connection.isIn(roomId)) {
			sendError(connection, "not_a_member", "not a member of room " + roomId);
			return;
		}
		long lastRead = rooms.markRead(roomId, connection.user().id(), seq.asLong());
		relay.toUser(connection.user().id(), json.writeValueAsString(ServerEvents.Read.of(roomId, lastRead)));
	}

	private void onSync(Connection connection, JsonNode node) {
		JsonNode requested = node.path("rooms");
		if (!requested.isObject() || requested.size() > MAX_SYNC_ROOMS) {
			sendError(connection, "invalid_sync", "rooms must be an object of roomId to last seen seq (max "
					+ MAX_SYNC_ROOMS + ")");
			return;
		}
		Map<UUID, Long> lastSeen = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> entry : requested.properties()) {
			UUID roomId = parseUuid(entry.getKey());
			JsonNode seq = entry.getValue();
			if (roomId == null || !seq.isIntegralNumber() || seq.asLong() < 0) {
				sendError(connection, "invalid_sync", "each entry must be a roomId and a seq of 0 or more");
				return;
			}
			if (!connection.isIn(roomId)) {
				sendError(connection, "not_a_member", "not a member of room " + roomId);
				return;
			}
			lastSeen.put(roomId, seq.asLong());
		}
		lastSeen.forEach((roomId, seq) -> {
			MessageService.SyncPage page = messages.since(roomId, seq);
			connection.send(json.writeValueAsString(
					ServerEvents.SyncMessages.of(roomId, page.messages(), page.truncated())));
		});
	}

	private void sendError(Connection connection, String code, String message) {
		connection.send(json.writeValueAsString(ServerEvents.Error.of(code, message)));
	}

	private static UUID parseUuid(String value) {
		try {
			return UUID.fromString(value);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}
}
