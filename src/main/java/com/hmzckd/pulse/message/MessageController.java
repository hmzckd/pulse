package com.hmzckd.pulse.message;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.hmzckd.pulse.auth.CurrentUser;
import com.hmzckd.pulse.room.RoomService;

@RestController
class MessageController {

	private final MessageService messages;
	private final RoomService rooms;

	MessageController(MessageService messages, RoomService rooms) {
		this.messages = messages;
		this.rooms = rooms;
	}

	@GetMapping("/api/rooms/{roomId}/messages")
	MessageService.HistoryPage history(@PathVariable UUID roomId, @RequestParam(required = false) Long before,
			@RequestParam(required = false) Integer limit, @AuthenticationPrincipal Jwt jwt) {
		rooms.requireMember(roomId, CurrentUser.from(jwt).id());
		return messages.history(roomId, before, limit);
	}
}
