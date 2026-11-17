package com.hmzckd.pulse.room;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hmzckd.pulse.auth.CurrentUser;

@RestController
@RequestMapping("/api/rooms")
class RoomController {

	private final RoomService rooms;

	RoomController(RoomService rooms) {
		this.rooms = rooms;
	}

	@PostMapping
	ResponseEntity<RoomResponse> create(@RequestBody CreateRoomRequest request, @AuthenticationPrincipal Jwt jwt) {
		RoomResponse room = rooms.create(request.name(), CurrentUser.from(jwt).id());
		return ResponseEntity.created(URI.create("/api/rooms/" + room.id())).body(room);
	}

	@GetMapping
	List<RoomResponse> list() {
		return rooms.list();
	}

	@GetMapping("/mine")
	List<MyRoomResponse> mine(@AuthenticationPrincipal Jwt jwt) {
		return rooms.roomsOf(CurrentUser.from(jwt).id());
	}

	@GetMapping("/{id}")
	RoomResponse get(@PathVariable UUID id) {
		return rooms.get(id);
	}

	@PostMapping("/{id}/members")
	ResponseEntity<Void> join(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
		rooms.join(id, CurrentUser.from(jwt).id());
		return ResponseEntity.noContent().build();
	}

	record CreateRoomRequest(String name) {
	}
}
