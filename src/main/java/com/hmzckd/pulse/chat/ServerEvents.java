package com.hmzckd.pulse.chat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.hmzckd.pulse.message.StoredMessage;

final class ServerEvents {

	private ServerEvents() {
	}

	record Ready(String type, String username, String server, List<UUID> rooms, Set<String> online) {

		static Ready of(String username, String server, List<UUID> rooms, Set<String> online) {
			return new Ready("ready", username, server, rooms, online);
		}
	}

	record Typing(String type, UUID roomId, String username) {

		static Typing of(UUID roomId, String username) {
			return new Typing("typing", roomId, username);
		}
	}

	record PresenceChanged(String type, String username, boolean online) {

		static PresenceChanged of(String username, boolean online) {
			return new PresenceChanged("presence", username, online);
		}
	}

	record MessageNew(String type, UUID id, UUID roomId, long seq, String sender, String text, Instant sentAt) {

		static MessageNew of(StoredMessage message) {
			return new MessageNew("message.new", message.id(), message.roomId(), message.seq(), message.sender(),
					message.text(), message.sentAt());
		}
	}

	record MessageAck(String type, UUID clientMsgId, UUID id, UUID roomId, long seq, Instant sentAt) {

		static MessageAck of(StoredMessage message) {
			return new MessageAck("message.ack", message.clientMsgId(), message.id(), message.roomId(), message.seq(),
					message.sentAt());
		}
	}

	record SyncMessages(String type, UUID roomId, List<MessageNew> messages, boolean truncated) {

		static SyncMessages of(UUID roomId, List<StoredMessage> messages, boolean truncated) {
			return new SyncMessages("sync.messages", roomId, messages.stream().map(MessageNew::of).toList(),
					truncated);
		}
	}

	record Read(String type, UUID roomId, long seq) {

		static Read of(UUID roomId, long seq) {
			return new Read("read", roomId, seq);
		}
	}

	record Error(String type, String code, String message) {

		static Error of(String code, String message) {
			return new Error("error", code, message);
		}
	}
}
