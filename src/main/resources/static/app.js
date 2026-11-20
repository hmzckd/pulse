'use strict';

const TYPING_EVERY_MS = 2000;
const TYPING_SHOWN_MS = 5000;
const GAP_WAIT_MS = 500;
const RETRY_MIN_MS = 1000;
const RETRY_MAX_MS = 30000;
const HISTORY_PAGE = 50;

const state = {
	username: null,
	token: null,
	ws: null,
	rooms: new Map(),
	activeRoomId: null,
	online: new Set(),
	pending: new Map(),
	typing: new Map(),
	lastTypingSentAt: 0,
	retryDelay: RETRY_MIN_MS,
	gapTimers: new Map(),
};

const $ = (id) => document.getElementById(id);

async function api(path, options = {}) {
	const response = await fetch(path, {
		...options,
		headers: {
			'Content-Type': 'application/json',
			...(state.token ? { Authorization: 'Bearer ' + state.token } : {}),
		},
	});
	if (!response.ok) {
		throw new Error(path + ' answered ' + response.status);
	}
	return response.status === 204 ? null : response.json();
}

async function login(username) {
	const session = await api('/api/sessions', { method: 'POST', body: JSON.stringify({ username }) });
	state.token = session.token;
	state.username = session.username;
}

function room(id) {
	if (!state.rooms.has(id)) {
		state.rooms.set(id, {
			id, name: '…', lastSeq: 0, lastReadSeq: 0, unread: 0,
			joined: false, loaded: false, firstSeq: null, messages: new Map(),
		});
	}
	return state.rooms.get(id);
}

async function loadRooms() {
	for (const mine of await api('/api/rooms/mine')) {
		const r = room(mine.id);
		r.name = mine.name;
		r.joined = true;
		r.lastSeq = Math.max(r.lastSeq, mine.lastSeq);
		r.lastReadSeq = Math.max(r.lastReadSeq, mine.lastReadSeq);
		r.unread = r.lastSeq - r.lastReadSeq;
	}
	for (const other of await api('/api/rooms')) {
		room(other.id).name = other.name;
	}
	renderRooms();
}

async function openRoom(id) {
	state.activeRoomId = id;
	const r = room(id);
	if (!r.loaded) {
		const page = await api('/api/rooms/' + id + '/messages?limit=' + HISTORY_PAGE);
		page.messages.forEach((m) => addMessage(m));
		r.firstSeq = page.messages.length ? page.messages[0].seq : r.lastSeq + 1;
		r.loaded = true;
	}
	markRead(r);
	renderAll();
	$('text').focus();
}

function contiguousSeq(r) {
	let seq = r.firstSeq - 1;
	while (r.messages.has(seq + 1)) {
		seq++;
	}
	return seq;
}

function addMessage(m) {
	const r = room(m.roomId);
	if (r.messages.has(m.seq)) {
		return;
	}
	r.messages.set(m.seq, m);
	r.lastSeq = Math.max(r.lastSeq, m.seq);
	if (m.sender === state.username) {
		r.lastReadSeq = Math.max(r.lastReadSeq, m.seq);
	}
	r.unread = r.lastSeq - r.lastReadSeq;
	watchForGap(r);
}

function watchForGap(r) {
	if (!r.loaded || contiguousSeq(r) >= r.lastSeq || state.gapTimers.has(r.id)) {
		return;
	}
	state.gapTimers.set(r.id, setTimeout(() => {
		state.gapTimers.delete(r.id);
		if (contiguousSeq(r) < r.lastSeq) {
			send({ type: 'sync', rooms: { [r.id]: contiguousSeq(r) } });
		}
	}, GAP_WAIT_MS));
}

function markRead(r) {
	if (r.lastSeq > r.lastReadSeq) {
		send({ type: 'read', roomId: r.id, seq: r.lastSeq });
		r.lastReadSeq = r.lastSeq;
		r.unread = 0;
	}
}

function send(frame) {
	if (state.ws && state.ws.readyState === WebSocket.OPEN) {
		state.ws.send(JSON.stringify(frame));
	}
}

function sendMessage(text) {
	const clientMsgId = crypto.randomUUID();
	state.pending.set(clientMsgId, { roomId: state.activeRoomId, text });
	send({ type: 'message.send', roomId: state.activeRoomId, clientMsgId, text });
	renderMessages();
}

function resendPending() {
	for (const [clientMsgId, p] of state.pending) {
		send({ type: 'message.send', roomId: p.roomId, clientMsgId, text: p.text });
	}
}

async function connect() {
	setStatus('connecting…', '');
	try {
		await login(state.username);
	}
	catch (error) {
		retryLater();
		return;
	}
	const scheme = location.protocol === 'https:' ? 'wss://' : 'ws://';
	const ws = new WebSocket(scheme + location.host + '/ws?token=' + state.token);
	state.ws = ws;
	ws.onmessage = (event) => handle(JSON.parse(event.data));
	ws.onclose = (event) => {
		if (state.ws === ws) {
			state.ws = null;
			retryLater(event.code);
		}
	};
}

function retryLater(code) {
	const delay = state.retryDelay * (0.5 + Math.random() / 2);
	state.retryDelay = Math.min(state.retryDelay * 2, RETRY_MAX_MS);
	const reason = code ? ' (closed ' + code + ')' : '';
	setStatus('retrying in ' + Math.round(delay / 1000) + ' s' + reason, 'retrying');
	setTimeout(connect, delay);
}

async function onReady(frame) {
	state.retryDelay = RETRY_MIN_MS;
	setStatus('online', 'online');
	$('server').textContent = 'server ' + frame.server.slice(0, 8);
	state.online = new Set(frame.online);
	await loadRooms();
	const loaded = [...state.rooms.values()].filter((r) => r.joined && r.loaded);
	if (loaded.length) {
		send({ type: 'sync', rooms: Object.fromEntries(loaded.map((r) => [r.id, contiguousSeq(r)])) });
	}
	resendPending();
	readActiveRoom();
	renderAll();
}

function handle(frame) {
	switch (frame.type) {
		case 'ready':
			onReady(frame);
			return;
		case 'message.ack':
			state.pending.delete(frame.clientMsgId);
			break;
		case 'message.new':
			addMessage(frame);
			break;
		case 'sync.messages':
			frame.messages.forEach((m) => addMessage(m));
			if (frame.truncated) {
				room(frame.roomId).loaded = false;
			}
			break;
		case 'typing':
			showTyping(frame.roomId, frame.username);
			break;
		case 'presence':
			frame.online ? state.online.add(frame.username) : state.online.delete(frame.username);
			break;
		case 'read': {
			const r = room(frame.roomId);
			r.lastReadSeq = Math.max(r.lastReadSeq, frame.seq);
			r.unread = r.lastSeq - r.lastReadSeq;
			break;
		}
		case 'error':
			if (frame.code === 'rate_limited') {
				setTimeout(resendPending, 3000);
			}
			console.warn(frame.code, frame.message);
			break;
	}
	readActiveRoom();
	renderAll();
}

function readActiveRoom() {
	if (state.activeRoomId && document.hasFocus()) {
		markRead(room(state.activeRoomId));
	}
}

function showTyping(roomId, username) {
	if (!state.typing.has(roomId)) {
		state.typing.set(roomId, new Map());
	}
	const typists = state.typing.get(roomId);
	clearTimeout(typists.get(username));
	typists.set(username, setTimeout(() => {
		typists.delete(username);
		renderTyping();
	}, TYPING_SHOWN_MS));
}

function setStatus(text, kind) {
	$('status').textContent = text;
	$('status').className = 'pill ' + kind;
}

function renderAll() {
	renderRooms();
	renderMessages();
	renderTyping();
}

function renderRooms() {
	const mine = $('my-rooms');
	const others = $('other-rooms');
	mine.replaceChildren();
	others.replaceChildren();
	for (const r of state.rooms.values()) {
		const li = document.createElement('li');
		li.textContent = r.name;
		if (r.joined) {
			li.classList.toggle('active', r.id === state.activeRoomId);
			if (r.unread > 0) {
				const badge = document.createElement('span');
				badge.className = 'badge';
				badge.textContent = r.unread;
				li.append(badge);
			}
			li.onclick = () => openRoom(r.id);
			mine.append(li);
		}
		else {
			const join = document.createElement('button');
			join.textContent = 'Join';
			join.onclick = async () => {
				await api('/api/rooms/' + r.id + '/members', { method: 'POST' });
				await loadRooms();
				openRoom(r.id);
			};
			li.append(join);
			others.append(li);
		}
	}
}

function renderMessages() {
	const r = state.activeRoomId && room(state.activeRoomId);
	$('room-title').textContent = r ? r.name : 'Pick a room';
	$('online').textContent = state.online.size ? 'online: ' + [...state.online].sort().join(', ') : '';
	$('text').disabled = !r;
	$('send-form').querySelector('button').disabled = !r;
	const list = $('messages');
	const atBottom = list.scrollHeight - list.scrollTop - list.clientHeight < 40;
	list.replaceChildren();
	if (!r) {
		return;
	}
	const sorted = [...r.messages.values()].sort((a, b) => a.seq - b.seq);
	for (const m of sorted) {
		list.append(bubble(m.sender, m.text, '#' + m.seq + ' ' + new Date(m.sentAt).toLocaleTimeString(), false));
	}
	for (const p of state.pending.values()) {
		if (p.roomId === r.id) {
			list.append(bubble(state.username, p.text, 'sending…', true));
		}
	}
	if (atBottom) {
		list.scrollTop = list.scrollHeight;
	}
}

function bubble(sender, text, info, pending) {
	const li = document.createElement('li');
	li.classList.toggle('mine', sender === state.username);
	li.classList.toggle('pending', pending);
	const meta = document.createElement('div');
	meta.className = 'meta';
	const who = document.createElement('span');
	const dot = document.createElement('span');
	dot.className = 'dot' + (sender === state.username || state.online.has(sender) ? ' on' : '');
	who.append(dot, sender);
	const when = document.createElement('span');
	when.textContent = info;
	meta.append(who, when);
	const body = document.createElement('div');
	body.textContent = text;
	li.append(meta, body);
	return li;
}

function renderTyping() {
	const typists = state.typing.get(state.activeRoomId);
	const names = typists ? [...typists.keys()] : [];
	$('typing').textContent = names.length ? names.join(', ') + (names.length > 1 ? ' are' : ' is') + ' typing…' : '';
}

$('login-form').onsubmit = (event) => {
	event.preventDefault();
	state.username = $('username').value.trim().toLowerCase();
	$('me').textContent = state.username;
	$('login-view').hidden = true;
	$('chat-view').hidden = false;
	connect();
};

$('create-form').onsubmit = async (event) => {
	event.preventDefault();
	const created = await api('/api/rooms', { method: 'POST', body: JSON.stringify({ name: $('room-name').value }) });
	$('room-name').value = '';
	await loadRooms();
	openRoom(created.id);
};

$('send-form').onsubmit = (event) => {
	event.preventDefault();
	const text = $('text').value.trim();
	if (text) {
		sendMessage(text);
		$('text').value = '';
	}
};

$('text').oninput = () => {
	const now = Date.now();
	if (state.activeRoomId && now - state.lastTypingSentAt > TYPING_EVERY_MS) {
		state.lastTypingSentAt = now;
		send({ type: 'typing', roomId: state.activeRoomId });
	}
};

window.onfocus = () => {
	readActiveRoom();
	renderRooms();
};
