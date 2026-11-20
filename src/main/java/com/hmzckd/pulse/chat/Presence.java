package com.hmzckd.pulse.chat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.hmzckd.pulse.auth.CurrentUser;
import com.hmzckd.pulse.room.Roommate;

@Component
class Presence {

	private static final Logger log = LoggerFactory.getLogger(Presence.class);
	static final Duration TTL = Duration.ofSeconds(30);
	static final long REFRESH_EVERY_MS = 10_000;

	private final StringRedisTemplate redis;
	private final ConnectionRegistry registry;
	private final Relay relay;
	private final String instanceId;

	Presence(StringRedisTemplate redis, ConnectionRegistry registry, Relay relay,
			@Value("${pulse.instance-id}") String instanceId) {
		this.redis = redis;
		this.registry = registry;
		this.relay = relay;
		this.instanceId = instanceId;
	}

	static String key(UUID userId) {
		return "presence:" + userId;
	}

	void cameOnline(CurrentUser user, Collection<UUID> rooms) {
		boolean wasOnline = isOnline(user.id());
		markHere(user.id());
		if (!wasOnline) {
			relay.announcePresence(user, rooms, true);
		}
	}

	void wentOffline(CurrentUser user, Collection<UUID> rooms) {
		redis.opsForZSet().remove(key(user.id()), instanceId);
		if (!isOnline(user.id())) {
			relay.announcePresence(user, rooms, false);
		}
	}

	Set<String> onlineAmong(List<Roommate> roommates) {
		Set<String> online = new TreeSet<>();
		if (roommates.isEmpty()) {
			return online;
		}
		double now = System.currentTimeMillis();
		List<Object> counts = redis.executePipelined((RedisCallback<Object>) connection -> {
			for (Roommate roommate : roommates) {
				byte[] key = key(roommate.id()).getBytes(StandardCharsets.UTF_8);
				connection.zSetCommands().zCount(key, now, Double.POSITIVE_INFINITY);
			}
			return null;
		});
		for (int i = 0; i < roommates.size(); i++) {
			if (counts.get(i) instanceof Long count && count > 0) {
				online.add(roommates.get(i).username());
			}
		}
		return online;
	}

	@Scheduled(fixedRate = REFRESH_EVERY_MS)
	void refresh() {
		try {
			for (UUID userId : registry.userIds()) {
				markHere(userId);
			}
		}
		catch (RuntimeException ex) {
			log.warn("Could not refresh presence: {}", ex.getMessage());
		}
	}

	private boolean isOnline(UUID userId) {
		Long count = redis.opsForZSet().count(key(userId), System.currentTimeMillis(), Double.POSITIVE_INFINITY);
		return count != null && count > 0;
	}

	private void markHere(UUID userId) {
		String key = key(userId);
		redis.opsForZSet().add(key, instanceId, System.currentTimeMillis() + TTL.toMillis());
		redis.expire(key, TTL);
	}
}
