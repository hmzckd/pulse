package com.hmzckd.pulse.auth;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class UserRepository {

	private final JdbcClient jdbc;

	UserRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	UUID findOrCreate(String username) {
		jdbc.sql("INSERT INTO users (id, username) VALUES (:id, :username) ON CONFLICT (username) DO NOTHING")
				.param("id", UUID.randomUUID())
				.param("username", username)
				.update();
		return jdbc.sql("SELECT id FROM users WHERE username = :username")
				.param("username", username)
				.query(UUID.class)
				.single();
	}
}
