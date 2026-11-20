package com.hmzckd.pulse.message;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class MessageRepository {

	private static final String SELECT_MESSAGE = """
			SELECT m.id, m.room_id, m.seq, u.username AS sender, m.client_msg_id, m.text, m.created_at
			FROM messages m JOIN users u ON u.id = m.sender_id
			""";

	private final JdbcClient jdbc;

	MessageRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	boolean lockRoom(UUID roomId) {
		return jdbc.sql("SELECT id FROM rooms WHERE id = :roomId FOR UPDATE")
				.param("roomId", roomId)
				.query(UUID.class)
				.optional()
				.isPresent();
	}

	long nextSeq(UUID roomId) {
		return jdbc.sql("UPDATE rooms SET last_seq = last_seq + 1 WHERE id = :roomId RETURNING last_seq")
				.param("roomId", roomId)
				.query(Long.class)
				.single();
	}

	void insert(UUID id, UUID roomId, long seq, UUID senderId, UUID clientMsgId, String text) {
		jdbc.sql("""
				INSERT INTO messages (id, room_id, seq, sender_id, client_msg_id, text)
				VALUES (:id, :roomId, :seq, :senderId, :clientMsgId, :text)
				""")
				.param("id", id)
				.param("roomId", roomId)
				.param("seq", seq)
				.param("senderId", senderId)
				.param("clientMsgId", clientMsgId)
				.param("text", text)
				.update();
	}

	void markReadBySender(UUID roomId, UUID senderId, long seq) {
		jdbc.sql("""
				UPDATE room_members SET last_read_seq = GREATEST(last_read_seq, :seq)
				WHERE room_id = :roomId AND user_id = :senderId
				""")
				.param("roomId", roomId)
				.param("senderId", senderId)
				.param("seq", seq)
				.update();
	}

	Optional<StoredMessage> findById(UUID id) {
		return jdbc.sql(SELECT_MESSAGE + " WHERE m.id = :id")
				.param("id", id)
				.query(MessageRepository::map)
				.optional();
	}

	Optional<StoredMessage> findByClientMsgId(UUID roomId, UUID senderId, UUID clientMsgId) {
		return jdbc.sql(SELECT_MESSAGE + """
				 WHERE m.room_id = :roomId AND m.sender_id = :senderId AND m.client_msg_id = :clientMsgId
				""")
				.param("roomId", roomId)
				.param("senderId", senderId)
				.param("clientMsgId", clientMsgId)
				.query(MessageRepository::map)
				.optional();
	}

	List<StoredMessage> findBefore(UUID roomId, long beforeSeq, int limit) {
		return jdbc.sql(SELECT_MESSAGE + """
				 WHERE m.room_id = :roomId AND m.seq < :before
				ORDER BY m.seq DESC
				LIMIT :limit
				""")
				.param("roomId", roomId)
				.param("before", beforeSeq)
				.param("limit", limit)
				.query(MessageRepository::map)
				.list();
	}

	List<StoredMessage> findAfter(UUID roomId, long afterSeq, int limit) {
		return jdbc.sql(SELECT_MESSAGE + """
				 WHERE m.room_id = :roomId AND m.seq > :after
				ORDER BY m.seq
				LIMIT :limit
				""")
				.param("roomId", roomId)
				.param("after", afterSeq)
				.param("limit", limit)
				.query(MessageRepository::map)
				.list();
	}

	private static StoredMessage map(ResultSet rs, int rowNum) throws SQLException {
		return new StoredMessage(
				rs.getObject("id", UUID.class),
				rs.getObject("room_id", UUID.class),
				rs.getLong("seq"),
				rs.getString("sender"),
				rs.getObject("client_msg_id", UUID.class),
				rs.getString("text"),
				rs.getObject("created_at", OffsetDateTime.class).toInstant());
	}
}
