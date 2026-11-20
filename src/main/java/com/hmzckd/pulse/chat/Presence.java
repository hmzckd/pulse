package com.hmzckd.pulse.chat;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

@Component
class Presence {

	private final ConnectionRegistry registry;
	private final JsonMapper json;

	Presence(ConnectionRegistry registry, JsonMapper json) {
		this.registry = registry;
		this.json = json;
	}

	void announce(Connection connection, boolean online) {
		send(registry.sharingRoomsWith(connection), connection, online);
	}

	void announceInRoom(Connection connection, UUID roomId) {
		List<Connection> others = registry.inRoom(roomId).stream()
				.filter(other -> !other.user().id().equals(connection.user().id()))
				.toList();
		send(others, connection, true);
	}

	private void send(Collection<Connection> recipients, Connection about, boolean online) {
		String payload = json.writeValueAsString(ServerEvents.PresenceChanged.of(about.user().username(), online));
		for (Connection recipient : recipients) {
			recipient.send(payload);
		}
	}
}
