package com.hmzckd.pulse.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class ServerEvents {

	private ServerEvents() {
	}

	record Ready(String type, String username, List<UUID> rooms) {

		static Ready of(String username, List<UUID> rooms) {
			return new Ready("ready", username, rooms);
		}
	}

	record MessageNew(String type, UUID roomId, String sender, String text, Instant sentAt) {

		static MessageNew of(UUID roomId, String sender, String text, Instant sentAt) {
			return new MessageNew("message.new", roomId, sender, text, sentAt);
		}
	}

	record Error(String type, String code, String message) {

		static Error of(String code, String message) {
			return new Error("error", code, message);
		}
	}
}
