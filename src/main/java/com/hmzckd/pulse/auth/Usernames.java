package com.hmzckd.pulse.auth;

import java.util.Locale;
import java.util.regex.Pattern;

import com.hmzckd.pulse.web.BadRequestException;

final class Usernames {

	private static final Pattern VALID = Pattern.compile("[a-z0-9_]{3,20}");

	private Usernames() {
	}

	static String normalize(String raw) {
		if (raw == null) {
			throw new BadRequestException("username is required");
		}
		String username = raw.trim().toLowerCase(Locale.ROOT);
		if (!VALID.matcher(username).matches()) {
			throw new BadRequestException("username must be 3-20 characters: a-z, 0-9 or _");
		}
		return username;
	}
}
