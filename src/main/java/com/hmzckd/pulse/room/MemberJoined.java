package com.hmzckd.pulse.room;

import java.util.UUID;

public record MemberJoined(UUID roomId, UUID userId) {
}
