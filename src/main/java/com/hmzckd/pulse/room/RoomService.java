package com.hmzckd.pulse.room;

import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hmzckd.pulse.web.BadRequestException;
import com.hmzckd.pulse.web.NotFoundException;

@Service
public class RoomService {

	static final int LIST_LIMIT = 100;

	private final RoomRepository rooms;
	private final ApplicationEventPublisher events;

	RoomService(RoomRepository rooms, ApplicationEventPublisher events) {
		this.rooms = rooms;
		this.events = events;
	}

	@Transactional
	public RoomResponse create(String rawName, UUID creatorId) {
		String name = rawName == null ? "" : rawName.trim();
		if (name.isEmpty() || name.length() > 50) {
			throw new BadRequestException("name must be 1-50 characters");
		}
		UUID id = UUID.randomUUID();
		rooms.insert(id, name, creatorId);
		rooms.addMember(id, creatorId);
		events.publishEvent(new MemberJoined(id, creatorId));
		return get(id);
	}

	public RoomResponse get(UUID id) {
		return rooms.findById(id).orElseThrow(() -> new NotFoundException("room " + id + " not found"));
	}

	public List<RoomResponse> list() {
		return rooms.findLatest(LIST_LIMIT);
	}

	@Transactional
	public void join(UUID roomId, UUID userId) {
		if (!rooms.exists(roomId)) {
			throw new NotFoundException("room " + roomId + " not found");
		}
		rooms.addMember(roomId, userId);
		events.publishEvent(new MemberJoined(roomId, userId));
	}

	public List<MyRoomResponse> roomsOf(UUID userId) {
		return rooms.findByMember(userId);
	}

	public List<UUID> roomIdsOf(UUID userId) {
		return rooms.findRoomIdsByMember(userId);
	}
}
