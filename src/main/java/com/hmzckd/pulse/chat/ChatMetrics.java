package com.hmzckd.pulse.chat;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Component
class ChatMetrics {

	private final Counter messagesSent;
	private final Counter rateLimited;
	private final Counter closedSlowConsumer;
	private final Counter closedNoPong;

	ChatMetrics(MeterRegistry registry, ConnectionRegistry connections) {
		Gauge.builder("pulse.connections.open", connections, ConnectionRegistry::count)
				.description("Open WebSocket connections on this server")
				.register(registry);
		this.messagesSent = Counter.builder("pulse.messages.sent")
				.description("Messages stored (duplicates not counted)")
				.register(registry);
		this.rateLimited = Counter.builder("pulse.messages.rate.limited")
				.description("message.send frames rejected by the per-connection limit")
				.register(registry);
		this.closedSlowConsumer = closed(registry, "slow_consumer");
		this.closedNoPong = closed(registry, "no_pong");
	}

	private static Counter closed(MeterRegistry registry, String reason) {
		return Counter.builder("pulse.connections.closed")
				.description("Connections the server closed on purpose")
				.tag("reason", reason)
				.register(registry);
	}

	void messageSent() {
		messagesSent.increment();
	}

	void rateLimited() {
		rateLimited.increment();
	}

	void closedByServer(CloseStatus status) {
		if (status.getCode() == Connection.SLOW_CONSUMER.getCode()) {
			closedSlowConsumer.increment();
		}
		else if (status.getCode() == Connection.NO_PONG.getCode()) {
			closedNoPong.increment();
		}
	}
}
