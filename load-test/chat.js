import http from 'k6/http';
import ws from 'k6/ws';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const servers = (__ENV.SERVERS || 'host.docker.internal:8081,host.docker.internal:8082').split(',');
const users = Number(__ENV.USERS || 5000);
const rooms = Number(__ENV.ROOMS || 100);
const rampSeconds = Number(__ENV.RAMP_SECONDS || 60);
const holdSeconds = Number(__ENV.HOLD_SECONDS || 120);
const sendEveryMs = Number(__ENV.SEND_EVERY_MS || 20000);

const delivery = new Trend('delivery_ms', true);
const connected = new Rate('ws_connected');
const sent = new Counter('messages_sent');
const acked = new Counter('messages_acked');
const received = new Counter('messages_received');
const errors = new Counter('server_errors');

export const options = {
	setupTimeout: '2m',
	scenarios: {
		chat: {
			executor: 'ramping-vus',
			startVUs: 0,
			stages: [
				{ duration: rampSeconds + 's', target: users },
				{ duration: holdSeconds + 's', target: users },
			],
			gracefulRampDown: '5s',
			gracefulStop: '10s',
		},
	},
	thresholds: {
		delivery_ms: ['p(95)<200'],
		ws_connected: ['rate>0.99'],
		server_errors: ['count==0'],
	},
};

function api(server, path, body, token) {
	const headers = { 'Content-Type': 'application/json' };
	if (token) {
		headers.Authorization = 'Bearer ' + token;
	}
	return http.post('http://' + server + path, body ? JSON.stringify(body) : null, { headers });
}

export function setup() {
	const run = Date.now().toString(36).slice(-5);
	const token = api(servers[0], '/api/sessions', { username: 'owner_' + run }).json('token');
	const roomIds = [];
	for (let i = 0; i < rooms; i++) {
		roomIds.push(api(servers[0], '/api/rooms', { name: 'load ' + i }, token).json('id'));
	}
	return { run, roomIds };
}

export default function (data) {
	const vu = exec.vu.idInTest;
	const endsAt = exec.scenario.startTime + (rampSeconds + holdSeconds) * 1000;
	const server = servers[vu % servers.length];
	const roomId = data.roomIds[vu % data.roomIds.length];
	const login = api(server, '/api/sessions', { username: 'u' + vu + '_' + data.run });
	const token = login.json('token');
	api(server, '/api/rooms/' + roomId + '/members', null, token);

	const response = ws.connect('ws://' + server + '/ws?token=' + token, null, (socket) => {
		socket.on('message', (raw) => {
			const frame = JSON.parse(raw);
			if (frame.type === 'message.new') {
				received.add(1);
				delivery.add(Date.now() - Number(frame.text.split(':')[1]));
			}
			else if (frame.type === 'message.ack') {
				acked.add(1);
			}
			else if (frame.type === 'error') {
				errors.add(1, { code: frame.code });
			}
		});
		const sendOne = () => {
			socket.send(JSON.stringify({
				type: 'message.send',
				roomId,
				clientMsgId: crypto.randomUUID(),
				text: 't:' + Date.now(),
			}));
			sent.add(1);
		};
		socket.setTimeout(() => {
			sendOne();
			socket.setInterval(sendOne, sendEveryMs);
		}, Math.random() * sendEveryMs);
		socket.setTimeout(() => socket.close(), Math.max(1000, endsAt - Date.now()));
	});
	connected.add(check(response, { 'websocket opened': (r) => r && r.status === 101 }));
}
