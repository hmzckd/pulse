# Glossary

| Term | Meaning |
|---|---|
| **User** | A person identified only by a unique, lowercase username (3-20 characters: `a-z`, `0-9`, `_`). Created on first login. |
| **Session token** | A short-lived JWT (HS256) returned by `POST /api/sessions`. The subject is the username and the `uid` claim is the user id. Sent as `Authorization: Bearer <token>`. |
| **Room** | A public chat room. Anyone can list rooms and join one. |
| **Member** | A user who has joined a room. Only members will receive and send messages in that room. The creator of a room is its first member. |
| **Connection** | One open WebSocket at `/ws`. A user can have several (one per browser tab). A connection listens to every room its user is a member of. |
| **Handshake** | The HTTP request that opens a WebSocket. Pulse checks the session token (`?token=`) and the `Origin` header here; a bad token gets `401`, another website gets `403`. |
| **Frame** | One JSON message on a connection. Every frame has a `type`: clients send `message.send`; the server sends `ready`, `message.ack`, `message.new` and `error`. |
| **Message** | A text (1-2000 characters) a member sent to a room. Stored before it is delivered. |
| **Sequence number (`seq`)** | The position of a message inside its room: 1, 2, 3... with no gaps. `rooms.last_seq` holds the latest one. |
| **Client message id (`clientMsgId`)** | A UUID the client puts on every `message.send`. Sending the same one again does not create a second message; the server answers with the first message's `message.ack`. |
| **Ack (`message.ack`)** | The server's answer to the sender: the message is stored, here is its `seq`. |
| **Last read sequence (`last_read_seq`)** | The highest `seq` a member has read in a room. Used for unread counts from PU-07 on. |
