package com.hmzckd.pulse.chat;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

record Envelope(String kind, UUID roomId, UUID userId, UUID skipUserId, List<UUID> rooms, String frame) {

	static final String ROOM = "room";
	static final String USER = "user";
	static final String JOINED = "joined";
	static final String PRESENCE = "presence";

	static Envelope toRoom(UUID roomId, UUID skipUserId, String frame) {
		return new Envelope(ROOM, roomId, null, skipUserId, null, frame);
	}

	static Envelope toUser(UUID userId, String frame) {
		return new Envelope(USER, null, userId, null, null, frame);
	}

	static Envelope joined(UUID userId, UUID roomId) {
		return new Envelope(JOINED, roomId, userId, null, null, null);
	}

	static Envelope presence(UUID userId, Collection<UUID> rooms, String frame) {
		return new Envelope(PRESENCE, null, userId, userId, List.copyOf(rooms), frame);
	}
}
