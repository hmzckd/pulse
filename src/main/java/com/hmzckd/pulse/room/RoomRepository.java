package com.hmzckd.pulse.room;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class RoomRepository {

	private static final String SELECT_ROOM = """
			SELECT r.id, r.name, u.username AS created_by, r.created_at
			FROM rooms r JOIN users u ON u.id = r.created_by
			""";

	private final JdbcClient jdbc;

	RoomRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void insert(UUID id, String name, UUID createdBy) {
		jdbc.sql("INSERT INTO rooms (id, name, created_by) VALUES (:id, :name, :createdBy)")
				.param("id", id)
				.param("name", name)
				.param("createdBy", createdBy)
				.update();
	}

	void addMember(UUID roomId, UUID userId) {
		jdbc.sql("""
				INSERT INTO room_members (room_id, user_id) VALUES (:roomId, :userId)
				ON CONFLICT (room_id, user_id) DO NOTHING
				""")
				.param("roomId", roomId)
				.param("userId", userId)
				.update();
	}

	boolean exists(UUID id) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM rooms WHERE id = :id)")
				.param("id", id)
				.query(Boolean.class)
				.single();
	}

	Optional<RoomResponse> findById(UUID id) {
		return jdbc.sql(SELECT_ROOM + " WHERE r.id = :id")
				.param("id", id)
				.query((rs, n) -> new RoomResponse(
						rs.getObject("id", UUID.class),
						rs.getString("name"),
						rs.getString("created_by"),
						toInstant(rs.getObject("created_at", OffsetDateTime.class))))
				.optional();
	}

	List<RoomResponse> findLatest(int limit) {
		return jdbc.sql(SELECT_ROOM + " ORDER BY r.created_at DESC, r.id LIMIT :limit")
				.param("limit", limit)
				.query((rs, n) -> new RoomResponse(
						rs.getObject("id", UUID.class),
						rs.getString("name"),
						rs.getString("created_by"),
						toInstant(rs.getObject("created_at", OffsetDateTime.class))))
				.list();
	}

	List<MyRoomResponse> findByMember(UUID userId) {
		return jdbc.sql("""
				SELECT r.id, r.name, m.joined_at
				FROM room_members m JOIN rooms r ON r.id = m.room_id
				WHERE m.user_id = :userId
				ORDER BY m.joined_at, r.id
				""")
				.param("userId", userId)
				.query((rs, n) -> new MyRoomResponse(
						rs.getObject("id", UUID.class),
						rs.getString("name"),
						toInstant(rs.getObject("joined_at", OffsetDateTime.class))))
				.list();
	}

	private static Instant toInstant(OffsetDateTime value) {
		return value.toInstant();
	}
}
