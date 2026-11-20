# Glossary

| Term | Meaning |
|---|---|
| **User** | A person identified only by a unique, lowercase username (3-20 characters: `a-z`, `0-9`, `_`). Created on first login. |
| **Session token** | A short-lived JWT (HS256) returned by `POST /api/sessions`. The subject is the username and the `uid` claim is the user id. Sent as `Authorization: Bearer <token>`. |
| **Room** | A public chat room. Anyone can list rooms and join one. |
| **Member** | A user who has joined a room. Only members will receive and send messages in that room. The creator of a room is its first member. |
| **Connection** | One open WebSocket at `/ws`. A user can have several (one per browser tab). A connection listens to every room its user is a member of. |
| **Handshake** | The HTTP request that opens a WebSocket. Pulse checks the session token (`?token=`) and the `Origin` header here; a bad token gets `401`, another website gets `403`. |
| **Frame** | One JSON message on a connection. Every frame has a `type`: clients send `message.send`, `sync`, `typing` and `read`; the server sends `ready`, `message.ack`, `message.new`, `sync.messages`, `typing`, `presence`, `read` and `error`. |
| **Message** | A text (1-2000 characters) a member sent to a room. Stored before it is delivered. |
| **Sequence number (`seq`)** | The position of a message inside its room: 1, 2, 3... with no gaps. `rooms.last_seq` holds the latest one. |
| **Client message id (`clientMsgId`)** | A UUID the client puts on every `message.send`. Sending the same one again does not create a second message; the server answers with the first message's `message.ack`. |
| **Sync** | After (re)connecting, the client sends `{"type":"sync","rooms":{"<roomId>":<last seen seq>}}`. The server answers one `sync.messages` frame per room with the messages after that seq (at most 500; `truncated: true` means fetch the rest from the history endpoint). A message can arrive both live and in a sync; clients drop any `seq` they already have. |
| **Typing** | A short-lived signal (`typing`) that a member is writing in a room. Never stored. Sent to the other members, at most once every 2 seconds per connection and room; clients hide it after 5 seconds without a new one. |
| **Presence** | Whether a user is online: online while at least one connection is open. Changes are sent as `presence` to users who share a room; `ready` lists who is already online. |
| **Ack (`message.ack`)** | The server's answer to the sender: the message is stored, here is its `seq`. |
| **Last read sequence (`last_read_seq`)** | The highest `seq` a member has read in a room. It only moves forward: the client sends `read` (roomId, seq), sending a message also marks it read for the sender, and new members start at the room's current `last_seq`. |
| **Unread count** | `rooms.last_seq - room_members.last_read_seq`, returned by `GET /api/rooms/mine`. No messages are counted. |
