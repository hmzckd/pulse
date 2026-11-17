package com.hmzckd.pulse.room;

import java.time.Instant;
import java.util.UUID;

public record RoomResponse(UUID id, String name, String createdBy, Instant createdAt) {
}
