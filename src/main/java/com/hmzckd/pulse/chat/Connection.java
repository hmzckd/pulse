package com.hmzckd.pulse.chat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.hmzckd.pulse.auth.CurrentUser;

public final class Connection {

	private static final Logger log = LoggerFactory.getLogger(Connection.class);

	private final WebSocketSession session;
	private final CurrentUser user;
	private final Set<UUID> rooms = ConcurrentHashMap.newKeySet();
	private final Map<UUID, Long> lastTypingAt = new ConcurrentHashMap<>();
	private final ReentrantLock gate = new ReentrantLock();
	private List<String> beforeReady = new ArrayList<>();

	Connection(WebSocketSession session, CurrentUser user) {
		this.session = session;
		this.user = user;
	}

	public String id() {
		return session.getId();
	}

	public CurrentUser user() {
		return user;
	}

	boolean isIn(UUID roomId) {
		return rooms.contains(roomId);
	}

	Set<UUID> rooms() {
		return rooms;
	}

	boolean mayAnnounceTyping(UUID roomId, long nowNanos, long intervalNanos) {
		boolean[] allowed = { false };
		lastTypingAt.compute(roomId, (id, last) -> {
			if (last == null || nowNanos - last >= intervalNanos) {
				allowed[0] = true;
				return nowNanos;
			}
			return last;
		});
		return allowed[0];
	}

	void send(String json) {
		gate.lock();
		try {
			if (beforeReady != null) {
				beforeReady.add(json);
				return;
			}
		}
		finally {
			gate.unlock();
		}
		write(json);
	}

	void sendReady(String readyFrame) {
		gate.lock();
		try {
			write(readyFrame);
			beforeReady.forEach(this::write);
			beforeReady = null;
		}
		finally {
			gate.unlock();
		}
	}

	private void write(String json) {
		try {
			session.sendMessage(new TextMessage(json));
		}
		catch (IOException | RuntimeException ex) {
			log.warn("Could not send to connection {} of {}: {}", id(), user.username(), ex.getMessage());
		}
	}
}
