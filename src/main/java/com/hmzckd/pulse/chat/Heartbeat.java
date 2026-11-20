package com.hmzckd.pulse.chat;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class Heartbeat {

	private final ConnectionRegistry registry;
	private final Duration pongTimeout;

	Heartbeat(ConnectionRegistry registry, @Value("${pulse.ws.pong-timeout}") Duration pongTimeout) {
		this.registry = registry;
		this.pongTimeout = pongTimeout;
	}

	@Scheduled(fixedRateString = "${pulse.ws.ping-interval}")
	void beat() {
		long now = System.nanoTime();
		for (Connection connection : registry.all()) {
			if (connection.silentLongerThan(pongTimeout, now)) {
				connection.close(Connection.NO_PONG);
			}
			else {
				connection.ping();
			}
		}
	}
}
