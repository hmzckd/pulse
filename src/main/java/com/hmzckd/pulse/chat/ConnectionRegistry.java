package com.hmzckd.pulse.chat;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.hmzckd.pulse.room.MemberJoined;

@Component
public class ConnectionRegistry {

	private final Map<String, Connection> byId = new ConcurrentHashMap<>();
	private final Map<UUID, Set<Connection>> byUser = new ConcurrentHashMap<>();
	private final Map<UUID, Set<Connection>> byRoom = new ConcurrentHashMap<>();

	void add(Connection connection) {
		byId.put(connection.id(), connection);
		byUser.computeIfAbsent(connection.user().id(), id -> ConcurrentHashMap.newKeySet()).add(connection);
	}

	void subscribe(Connection connection, UUID roomId) {
		connection.rooms().add(roomId);
		byRoom.computeIfAbsent(roomId, id -> ConcurrentHashMap.newKeySet()).add(connection);
	}

	void remove(String connectionId) {
		Connection connection = byId.remove(connectionId);
		if (connection == null) {
			return;
		}
		removeFrom(byUser, connection.user().id(), connection);
		for (UUID roomId : connection.rooms()) {
			removeFrom(byRoom, roomId, connection);
		}
	}

	Connection get(String connectionId) {
		return byId.get(connectionId);
	}

	Collection<Connection> inRoom(UUID roomId) {
		Set<Connection> connections = byRoom.get(roomId);
		return connections == null ? List.of() : connections;
	}

	public int count() {
		return byId.size();
	}

	@TransactionalEventListener
	void onMemberJoined(MemberJoined event) {
		Set<Connection> connections = byUser.get(event.userId());
		if (connections == null) {
			return;
		}
		for (Connection connection : connections) {
			subscribe(connection, event.roomId());
		}
	}

	private static void removeFrom(Map<UUID, Set<Connection>> index, UUID key, Connection connection) {
		index.computeIfPresent(key, (k, set) -> {
			set.remove(connection);
			return set.isEmpty() ? null : set;
		});
	}
}
