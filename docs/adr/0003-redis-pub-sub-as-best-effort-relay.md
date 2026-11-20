# 0003: Redis pub/sub as a best-effort relay between servers

## Status

Accepted

## Context

With two Pulse servers, Ali can be connected to server A and Ayşe to server B. Server A stores Ali's message, but it
does not hold Ayşe's connection, so it cannot hand the message to her. The same is true for typing signals, read
markers (a user's tabs can be on different servers), new memberships and presence.

Options:

- **Redis pub/sub:** fire-and-forget. A message published while a server is not subscribed, or while its connection
  to Redis is down, is lost.
- **Redis Streams or Kafka:** durable, with consumer offsets. A second store of every message, plus offsets to manage.
- **Sticky sessions behind a load balancer:** does not help, Ali and Ayşe still sit on different servers.

The database is already the source of truth: every message has a gapless `seq` (ADR 0002) and clients can ask for
what they missed with `sync` (PU-05).

## Decision

Use Redis pub/sub as a best-effort relay.

- One channel per room (`room:<id>`) for `message.new` and `typing`, one per user (`user:<id>`) for `read` and
  "joined a room", and one shared `presence` channel.
- A server subscribes to `room:<id>` when its first local connection in that room opens and unsubscribes when the last
  one closes; the same for `user:<id>`. Subscribing waits for Redis to confirm, so a connection gets `ready` only after
  live delivery works (the "too many, never too few" rule of PU-05 still holds).
- There is one delivery path: the server that receives a message stores it, sends the `message.ack` to the sender,
  and publishes `message.new`. Every server, including the one that stored it, delivers it from Redis to its own
  connections.
- Presence lives in Redis: `presence:<userId>` is a sorted set of server ids, scored with "valid until" time. Each
  server refreshes its entries every 10 seconds with a 30 second lifetime, so a crashed server's users go offline by
  themselves after at most 30 seconds.

## Consequences

- Any number of servers can share the work, with no sticky sessions.
- If Redis drops a message, the client sees a gap in `seq` (7 after 5) or misses it until the next reconnect, and
  fetches it with `sync`. Nothing is lost, because nothing is stored only in Redis.
- If Redis is down, messages are still stored and acknowledged, but not delivered live; new connections fail until
  Redis is back (Spring's listener container reconnects and subscribes again by itself).
- Frames from Redis are handed out on virtual threads, so two messages can reach a client out of order. Clients sort
  by `seq`.
- The extra hop through Redis costs a few milliseconds even when both users are on the same server. The load test
  (PU-11) measures it.
- A crashed server cannot announce its users as offline. Other users see them as online until they reload or the
  users reconnect elsewhere.
