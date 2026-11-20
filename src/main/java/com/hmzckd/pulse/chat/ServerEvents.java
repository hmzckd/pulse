package com.hmzckd.pulse.chat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.hmzckd.pulse.message.StoredMessage;

final class ServerEvents {

	private ServerEvents() {
	}

	record Ready(String type, String username, List<UUID> rooms) {

		static Ready of(String username, List<UUID> rooms) {
			return new Ready("ready", username, rooms);
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

	record Error(String type, String code, String message) {

		static Error of(String code, String message) {
			return new Error("error", code, message);
		}
	}
}
