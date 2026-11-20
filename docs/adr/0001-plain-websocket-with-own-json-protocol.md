# 0001: Plain WebSocket with our own JSON protocol, not STOMP

## Status

Accepted

## Context

Pulse pushes chat messages, typing and presence events to browsers over long-lived connections. Spring offers two
ways to do this: a plain `WebSocketHandler`, or STOMP on top of WebSocket with a message broker (in-memory or a relay
to RabbitMQ/ActiveMQ) that manages subscriptions for us.

## Decision

Use a plain `WebSocketHandler` at `/ws`. Every frame is a JSON object with a `type` field (`message.send`,
`message.new`, `ready`, `error`, ...). The server decides which rooms a connection listens to from room membership in
the database; clients do not send subscribe frames.

## Consequences

- We own the routing code (`ConnectionRegistry`) and can explain and test every step: who receives a message and why.
- Sequence numbers, catch-up after reconnect (`sync`) and cross-instance fan-out through Redis are designed around our
  own frames instead of being bent to fit STOMP destinations.
- Clients need no STOMP library; a browser's built-in `WebSocket` is enough, and so is k6 for load tests.
- We do not get STOMP features for free: receipts, heartbeats and a broker relay. Heartbeats (ping/pong) and
  acknowledgements (`message.ack`) are added by us in later steps.
- Authentication happens once, in the handshake (`?token=`), because browsers cannot set an `Authorization` header on
  a WebSocket request.
