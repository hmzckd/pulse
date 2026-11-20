# 0004: Close slow and silent connections, limit messages per connection

## Status

Accepted

## Context

A server holds thousands of connections. Three kinds of clients can hurt everybody else:

- **Slow consumers:** a phone on a bad network reads frames slower than the room produces them. Frames waiting for it
  pile up in the server's memory.
- **Silent connections:** a phone enters a tunnel and disappears without a close frame. The server keeps the
  connection, the user looks online forever, and proxies in between usually cut idle connections after about 60
  seconds anyway.
- **Spammers:** one connection sends hundreds of messages a second, and every one of them is stored and fanned out.

## Decision

- Every connection is wrapped in Spring's `ConcurrentWebSocketSessionDecorator` with a 5 second send time limit and a
  512 KB buffer limit. When a limit is hit, Pulse closes the connection with code `4008` ("slow consumer"). The client
  reconnects and catches up with `sync`. The close goes to the underlying session, because the decorator would replace
  any code with `4500` once a limit was hit.
- The server sends a ping every 25 seconds. A connection with no pong for 60 seconds is closed with code `4000`
  ("no pong"). Browsers answer pings by themselves.
- Each connection may send 20 `message.send` frames per 10 seconds (a token bucket that refills 2 per second). The
  rest get `error` with code `rate_limited`; the connection stays open and the client can retry with the same
  `clientMsgId`.

## Consequences

- One slow or dead client can no longer grow the server's memory or keep a user online.
- A client on a bad network may be disconnected and reconnect several times. Nothing is lost, because messages are
  stored and fetched with `sync`.
- The rate limit is per connection and in memory. A user with five tabs can send five times as much, and the limit is
  not shared between servers. A distributed limiter (like the one in QuotaGate) could replace it; for a chat this
  basic protection is enough.
