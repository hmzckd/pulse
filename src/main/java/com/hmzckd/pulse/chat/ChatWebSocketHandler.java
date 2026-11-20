package com.hmzckd.pulse.chat;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
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
	private static final int SEND_TIME_LIMIT_MS = 5_000;
	private static final int BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;

	private final ConnectionRegistry registry;
	private final RoomService rooms;
	private final MessageService messages;
	private final JsonMapper json;

	ChatWebSocketHandler(ConnectionRegistry registry, RoomService rooms, MessageService messages, JsonMapper json) {
		this.registry = registry;
		this.rooms = rooms;
		this.messages = messages;
		this.json = json;
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		CurrentUser user = (CurrentUser) session.getAttributes().get(TokenHandshakeInterceptor.USER_ATTRIBUTE);
		WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS,
				BUFFER_SIZE_LIMIT_BYTES);
		Connection connection = new Connection(safe, user);
		registry.add(connection);
		List<UUID> roomIds = rooms.roomIdsOf(user.id());
		for (UUID roomId : roomIds) {
			registry.subscribe(connection, roomId);
		}
		connection.send(json.writeValueAsString(ServerEvents.Ready.of(user.username(), roomIds)));
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
			default -> sendError(connection, "unknown_type", "unknown message type: " + type);
		}
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		registry.remove(session.getId());
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
		String payload = json.writeValueAsString(ServerEvents.MessageNew.of(result.message()));
		for (Connection member : registry.inRoom(roomId)) {
			member.send(payload);
		}
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
