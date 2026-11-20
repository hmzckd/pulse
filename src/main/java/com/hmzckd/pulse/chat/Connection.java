package com.hmzckd.pulse.chat;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.SessionLimitExceededException;

import com.hmzckd.pulse.auth.CurrentUser;

public final class Connection {

	private static final Logger log = LoggerFactory.getLogger(Connection.class);

	static final CloseStatus SLOW_CONSUMER = new CloseStatus(4008, "slow consumer");
	static final CloseStatus NO_PONG = new CloseStatus(4000, "no pong");
	static final int MESSAGE_LIMIT = 20;
	static final Duration MESSAGE_WINDOW = Duration.ofSeconds(10);

	private final ConcurrentWebSocketSessionDecorator session;
	private final CurrentUser user;
	private final ChatMetrics metrics;
	private final Set<UUID> rooms = ConcurrentHashMap.newKeySet();
	private final Map<UUID, Long> lastTypingAt = new ConcurrentHashMap<>();
	private final RateLimiter messages;
	private final ReentrantLock gate = new ReentrantLock();
	private List<String> beforeReady = new ArrayList<>();
	private volatile long lastPongAt;
	private final AtomicBoolean closing = new AtomicBoolean();

	Connection(ConcurrentWebSocketSessionDecorator session, CurrentUser user, ChatMetrics metrics, long nowNanos) {
		this.session = session;
		this.user = user;
		this.metrics = metrics;
		this.messages = new RateLimiter(MESSAGE_LIMIT, MESSAGE_WINDOW, nowNanos);
		this.lastPongAt = nowNanos;
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

	boolean maySendMessage(long nowNanos) {
		return messages.tryAcquire(nowNanos);
	}

	void pongReceived(long nowNanos) {
		lastPongAt = nowNanos;
	}

	boolean silentLongerThan(Duration timeout, long nowNanos) {
		return nowNanos - lastPongAt > timeout.toNanos();
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
		write(new TextMessage(json));
	}

	void sendReady(String readyFrame) {
		gate.lock();
		try {
			write(new TextMessage(readyFrame));
			beforeReady.forEach(json -> write(new TextMessage(json)));
			beforeReady = null;
		}
		finally {
			gate.unlock();
		}
	}

	void ping() {
		write(new PingMessage());
	}

	void close(CloseStatus status) {
		if (!closing.compareAndSet(false, true)) {
			return;
		}
		metrics.closedByServer(status);
		try {
			session.getDelegate().close(status);
		}
		catch (IOException | RuntimeException ex) {
			log.warn("Could not close connection {} of {}: {}", id(), user.username(), ex.getMessage());
		}
	}

	private void write(WebSocketMessage<?> message) {
		try {
			session.sendMessage(message);
		}
		catch (SessionLimitExceededException ex) {
			log.warn("Closing slow connection {} of {}: {}", id(), user.username(), ex.getMessage());
			close(SLOW_CONSUMER);
		}
		catch (IOException | RuntimeException ex) {
			log.warn("Could not send to connection {} of {}: {}", id(), user.username(), ex.getMessage());
		}
	}
}
