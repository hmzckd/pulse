# Load test

[`load-test/chat.js`](../load-test/chat.js) is a [k6](https://k6.io/) script that checks two claims: one Pulse server
holds thousands of open WebSocket connections, and a message reaches the other members of its room quickly even when
the sender and the readers are on different servers.

The test is run by hand, not in CI. It takes a few minutes and needs a machine whose numbers mean something.

## Scenario

- 100 rooms, 5,000 users, 50 users per room.
- Users connect over 60 seconds, alternating between server A (`8081`) and server B (`8082`), then stay connected for
  2 more minutes.
- Every user sends one message every 20 seconds (about 250 messages a second in total). Each message is delivered to
  the 50 members of its room, so the servers push about 12,500 frames a second.
- The message text is the time it was sent. When a `message.new` arrives, k6 subtracts that time from the current time:
  this is `delivery_ms`, from "the client sent it" to "a member received it", including saving it in PostgreSQL and the
  hop through Redis.

Pass criteria (k6 thresholds): `delivery_ms` p95 under 200 ms, more than 99% of the connections opened, and no
`error` frames from the server.

## Run it

1. Start PostgreSQL, Redis and the two instances:

   ```powershell
   docker compose up -d --build
   ```

2. From the repository folder, run k6 in Docker:

   ```powershell
   Get-Content load-test\chat.js | docker run --rm -i grafana/k6:1.3.0 run -
   ```

   Smaller runs: `-e USERS=1000 -e ROOMS=20`. Other knobs: `RAMP_SECONDS`, `HOLD_SECONDS`, `SEND_EVERY_MS`,
   `SERVERS` (default `host.docker.internal:8081,host.docker.internal:8082`).

3. While it runs, watch a server: `http://localhost:8081/actuator/prometheus`, look for `pulse_connections_open`.

## Results

Both servers, PostgreSQL, Redis and k6 on one Linux machine with 4 vCPUs and 16 GB of memory, so k6 competed with
the servers for the same CPUs.

| Metric | Result |
|---|---|
| Connections opened | 5,222 of 5,222 (100%), 5,000 at the same time |
| Messages sent / acknowledged | 37,499 / 37,454 (the rest were in flight when the test stopped) |
| Messages delivered | 1,745,866 (about 9,500 a second) |
| `delivery_ms` p50 | 7 ms |
| `delivery_ms` p90 | 66 ms |
| `delivery_ms` p95 | 135 ms |
| `delivery_ms` max | 1.74 s |
| `error` frames | 0 |
| Slow consumer closes | 0 |

The p95 target (200 ms) was met. The numbers cover the whole run, including the first minute in which thousands of
users were logging in, joining rooms and connecting while messages were already flowing.
