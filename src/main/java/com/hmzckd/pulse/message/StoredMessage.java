package com.hmzckd.pulse.message;

import java.time.Instant;
import java.util.UUID;

public record StoredMessage(UUID id, UUID roomId, long seq, String sender, UUID clientMsgId, String text,
		Instant sentAt) {
}
