package com.hmzckd.pulse.chat;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ServerInstance {

	private final String id;

	ServerInstance(@Value("${pulse.instance-id:}") String configured) {
		this.id = configured.isBlank() ? UUID.randomUUID().toString() : configured;
	}

	public String id() {
		return id;
	}
}
