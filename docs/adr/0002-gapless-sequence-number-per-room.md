# 0002: Gapless sequence number per room

## Status

Accepted

## Context

Clients must be able to tell whether they missed a message: after a reconnect, when Redis drops a pub/sub message
(PU-08), or when two frames arrive out of order. Unread counts should be cheap to compute. A global `bigserial` id
gives every message a unique number, but the numbers inside one room have gaps (other rooms use the values in
between, and sequences skip values on rollback), so a client cannot tell "missed" from "belongs to another room".

## Decision

Each room has a counter, `rooms.last_seq`. Saving a message runs in one transaction:

1. `SELECT ... FROM rooms WHERE id = ? FOR UPDATE` locks the room row.
2. If a message with the same `(room_id, sender_id, client_msg_id)` exists, return it (duplicate send).
3. `UPDATE rooms SET last_seq = last_seq + 1 WHERE id = ? RETURNING last_seq` gives the new `seq`.
4. Insert the message with primary key `(room_id, seq)`.

The server broadcasts only after the commit.

## Consequences

- Inside a room, messages are numbered 1, 2, 3... with no gaps and no duplicates, even under concurrent senders
  (tested with 40 parallel writers).
- A client that sees `seq` 7 right after 5 knows it missed 6 and can ask for it (`sync`, PU-05).
- Unread count is `rooms.last_seq - room_members.last_read_seq`, no `COUNT(*)` (PU-07).
- Writes to the same room are serialized by the row lock. A chat room sees a few messages per second, so this is
  fine; the load test (PU-11) measures it. Different rooms never wait for each other.
- Taking the lock before the duplicate check means a retried send can never create a gap: the check and the
  increment happen while no one else can write to the room.
