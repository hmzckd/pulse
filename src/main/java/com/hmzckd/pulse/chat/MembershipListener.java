package com.hmzckd.pulse.chat;

import java.util.Collection;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.hmzckd.pulse.room.MemberJoined;

@Component
class MembershipListener {

	private final ConnectionRegistry registry;
	private final Presence presence;

	MembershipListener(ConnectionRegistry registry, Presence presence) {
		this.registry = registry;
		this.presence = presence;
	}

	@TransactionalEventListener
	void onMemberJoined(MemberJoined event) {
		Collection<Connection> connections = registry.ofUser(event.userId());
		if (connections.isEmpty()) {
			return;
		}
		for (Connection connection : connections) {
			registry.subscribe(connection, event.roomId());
		}
		presence.announceInRoom(connections.iterator().next(), event.roomId());
	}
}
