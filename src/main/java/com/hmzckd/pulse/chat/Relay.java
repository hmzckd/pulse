package com.hmzckd.pulse.chat;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import com.hmzckd.pulse.auth.CurrentUser;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
class Relay implements MessageListener {

	private static final Logger log = LoggerFactory.getLogger(Relay.class);
	static final String PRESENCE_CHANNEL = "presence";

	private final StringRedisTemplate redis;
	private final RedisMessageListenerContainer container;
	private final ConnectionRegistry registry;
	private final JsonMapper json;
	private final Set<String> listening = new HashSet<>();
	private final ReentrantLock lock = new ReentrantLock();

	Relay(StringRedisTemplate redis, RedisMessageListenerContainer container, ConnectionRegistry registry,
			JsonMapper json) {
		this.redis = redis;
		this.container = container;
		this.registry = registry;
		this.json = json;
		container.addMessageListener(this, new ChannelTopic(PRESENCE_CHANNEL));
	}

	static String roomChannel(UUID roomId) {
		return "room:" + roomId;
	}

	static String userChannel(UUID userId) {
		return "user:" + userId;
	}

	void toRoom(UUID roomId, UUID skipUserId, String frame) {
		publish(roomChannel(roomId), Envelope.toRoom(roomId, skipUserId, frame));
	}

	void toUser(UUID userId, String frame) {
		publish(userChannel(userId), Envelope.toUser(userId, frame));
	}

	void joined(UUID userId, UUID roomId) {
		publish(userChannel(userId), Envelope.joined(userId, roomId));
	}

	void announcePresence(CurrentUser user, Collection<UUID> rooms, boolean online) {
		String frame = json.writeValueAsString(ServerEvents.PresenceChanged.of(user.username(), online));
		publish(PRESENCE_CHANNEL, Envelope.presence(user.id(), rooms, frame));
	}

	Collection<Connection> subscribeUser(UUID userId, UUID roomId) {
		Collection<Connection> connections = registry.ofUser(userId);
		for (Connection connection : connections) {
			registry.subscribe(connection, roomId);
		}
		refreshRoom(roomId);
		return connections;
	}

	void refresh(Connection connection) {
		refreshUser(connection.user().id());
		for (UUID roomId : connection.rooms()) {
			refreshRoom(roomId);
		}
	}

	void refreshRoom(UUID roomId) {
		update(roomChannel(roomId), () -> !registry.inRoom(roomId).isEmpty());
	}

	void refreshUser(UUID userId) {
		update(userChannel(userId), () -> !registry.ofUser(userId).isEmpty());
	}

	private void update(String channel, BooleanSupplier wanted) {
		lock.lock();
		try {
			boolean want = wanted.getAsBoolean();
			if (want && !listening.contains(channel)) {
				container.addMessageListener(this, new ChannelTopic(channel));
				listening.add(channel);
			}
			else if (!want && listening.contains(channel)) {
				listening.remove(channel);
				container.removeMessageListener(this, new ChannelTopic(channel));
			}
		}
		finally {
			lock.unlock();
		}
	}

	private void publish(String channel, Envelope envelope) {
		try {
			redis.convertAndSend(channel, json.writeValueAsString(envelope));
		}
		catch (RuntimeException ex) {
			log.warn("Could not publish {} to {}: {}", envelope.kind(), channel, ex.getMessage());
		}
	}

	@Override
	public void onMessage(Message message, byte[] pattern) {
		Envelope envelope;
		try {
			envelope = json.readValue(message.getBody(), Envelope.class);
		}
		catch (JacksonException ex) {
			log.warn("Ignoring unreadable message on {}", new String(message.getChannel(), StandardCharsets.UTF_8));
			return;
		}
		switch (envelope.kind()) {
			case Envelope.ROOM -> deliver(registry.inRoom(envelope.roomId()), envelope);
			case Envelope.USER -> deliver(registry.ofUser(envelope.userId()), envelope);
			case Envelope.PRESENCE -> deliver(inAnyOf(envelope.rooms()), envelope);
			case Envelope.JOINED -> onJoined(envelope.userId(), envelope.roomId());
			default -> log.warn("Ignoring unknown kind {}", envelope.kind());
		}
	}

	private void onJoined(UUID userId, UUID roomId) {
		Collection<Connection> connections = subscribeUser(userId, roomId);
		if (!connections.isEmpty()) {
			announcePresence(connections.iterator().next().user(), List.of(roomId), true);
		}
	}

	private void deliver(Collection<Connection> recipients, Envelope envelope) {
		for (Connection recipient : recipients) {
			if (!recipient.user().id().equals(envelope.skipUserId())) {
				recipient.send(envelope.frame());
			}
		}
	}

	private Set<Connection> inAnyOf(List<UUID> rooms) {
		Set<Connection> connections = new HashSet<>();
		for (UUID roomId : rooms) {
			connections.addAll(registry.inRoom(roomId));
		}
		return connections;
	}
}
