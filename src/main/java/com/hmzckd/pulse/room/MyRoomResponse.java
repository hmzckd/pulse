package com.hmzckd.pulse.room;

import java.time.Instant;
import java.util.UUID;

public record MyRoomResponse(UUID id, String name, Instant joinedAt, long lastSeq, long lastReadSeq, long unread) {
}
