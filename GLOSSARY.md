# Glossary

| Term | Meaning |
|---|---|
| **User** | A person identified only by a unique, lowercase username (3-20 characters: `a-z`, `0-9`, `_`). Created on first login. |
| **Session token** | A short-lived JWT (HS256) returned by `POST /api/sessions`. The subject is the username and the `uid` claim is the user id. Sent as `Authorization: Bearer <token>`. |
| **Room** | A public chat room. Anyone can list rooms and join one. |
| **Member** | A user who has joined a room. Only members will receive and send messages in that room. The creator of a room is its first member. |
| **Sequence number (`seq`)** | The position of a message inside its room: 1, 2, 3... with no gaps. `rooms.last_seq` holds the latest one. Used from PU-04 on. |
| **Last read sequence (`last_read_seq`)** | The highest `seq` a member has read in a room. Used for unread counts from PU-07 on. |
