package com.hmzckd.pulse.chat;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class ConnectionRegistry {

	private final Map<String, Connection> byId = new ConcurrentHashMap<>();
	private final Map<UUID, Set<Connection>> byUser = new ConcurrentHashMap<>();
	private final Map<UUID, Set<Connection>> byRoom = new ConcurrentHashMap<>();

	boolean add(Connection connection) {
		byId.put(connection.id(), connection);
		boolean[] first = { false };
		byUser.compute(connection.user().id(), (id, set) -> {
			Set<Connection> connections = set == null ? ConcurrentHashMap.newKeySet() : set;
			first[0] = connections.isEmpty();
			connections.add(connection);
			return connections;
		});
		return first[0];
	}

	void subscribe(Connection connection, UUID roomId) {
		connection.rooms().add(roomId);
		byRoom.computeIfAbsent(roomId, id -> ConcurrentHashMap.newKeySet()).add(connection);
	}

	Removed remove(String connectionId) {
		Connection connection = byId.remove(connectionId);
		if (connection == null) {
			return null;
		}
		boolean last = removeFrom(byUser, connection.user().id(), connection);
		for (UUID roomId : connection.rooms()) {
			removeFrom(byRoom, roomId, connection);
		}
		return new Removed(connection, last);
	}

	record Removed(Connection connection, boolean lastForUser) {
	}

	Collection<Connection> sharingRoomsWith(Connection connection) {
		Set<Connection> result = new HashSet<>();
		for (UUID roomId : connection.rooms()) {
			for (Connection other : inRoom(roomId)) {
				if (!other.user().id().equals(connection.user().id())) {
					result.add(other);
				}
			}
		}
		return result;
	}

	Set<String> onlineUsernamesSharingRoomsWith(Connection connection) {
		Set<String> names = new TreeSet<>();
		for (Connection other : sharingRoomsWith(connection)) {
			names.add(other.user().username());
		}
		return names;
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

	Collection<Connection> ofUser(UUID userId) {
		Set<Connection> connections = byUser.get(userId);
		return connections == null ? List.of() : connections;
	}

	private static boolean removeFrom(Map<UUID, Set<Connection>> index, UUID key, Connection connection) {
		boolean[] emptied = { false };
		index.computeIfPresent(key, (k, set) -> {
			set.remove(connection);
			emptied[0] = set.isEmpty();
			return emptied[0] ? null : set;
		});
		return emptied[0];
	}
}
