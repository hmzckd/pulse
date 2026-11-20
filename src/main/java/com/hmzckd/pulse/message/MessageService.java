package com.hmzckd.pulse.message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hmzckd.pulse.web.BadRequestException;
import com.hmzckd.pulse.web.NotFoundException;

@Service
public class MessageService {

	public static final int MAX_TEXT_LENGTH = 2000;
	static final int DEFAULT_PAGE_SIZE = 50;
	static final int MAX_PAGE_SIZE = 100;

	private final MessageRepository messages;

	MessageService(MessageRepository messages) {
		this.messages = messages;
	}

	@Transactional
	public SaveResult save(UUID roomId, UUID senderId, UUID clientMsgId, String text) {
		if (!messages.lockRoom(roomId)) {
			throw new NotFoundException("room " + roomId + " not found");
		}
		var existing = messages.findByClientMsgId(roomId, senderId, clientMsgId);
		if (existing.isPresent()) {
			return new SaveResult(existing.get(), true);
		}
		long seq = messages.nextSeq(roomId);
		UUID id = UUID.randomUUID();
		messages.insert(id, roomId, seq, senderId, clientMsgId, text);
		return new SaveResult(messages.findById(id).orElseThrow(), false);
	}

	public HistoryPage history(UUID roomId, Long before, Integer limit) {
		int size = limit == null ? DEFAULT_PAGE_SIZE : limit;
		if (size < 1 || size > MAX_PAGE_SIZE) {
			throw new BadRequestException("limit must be 1-" + MAX_PAGE_SIZE);
		}
		if (before != null && before < 1) {
			throw new BadRequestException("before must be positive");
		}
		List<StoredMessage> page = new ArrayList<>(
				messages.findBefore(roomId, before == null ? Long.MAX_VALUE : before, size));
		Collections.reverse(page);
		Long nextBefore = page.size() == size && page.getFirst().seq() > 1 ? page.getFirst().seq() : null;
		return new HistoryPage(page, nextBefore);
	}

	public record SaveResult(StoredMessage message, boolean duplicate) {
	}

	public record HistoryPage(List<StoredMessage> messages, Long nextBefore) {
	}
}
