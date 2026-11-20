package com.hmzckd.pulse.chat;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.hmzckd.pulse.room.MemberJoined;

@Component
class MembershipListener {

	private final Relay relay;

	MembershipListener(Relay relay) {
		this.relay = relay;
	}

	@TransactionalEventListener
	void onMemberJoined(MemberJoined event) {
		relay.subscribeUser(event.userId(), event.roomId());
		relay.joined(event.userId(), event.roomId());
	}
}
