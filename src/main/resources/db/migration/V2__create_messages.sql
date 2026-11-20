CREATE TABLE messages (
    room_id       UUID NOT NULL REFERENCES rooms (id),
    seq           BIGINT NOT NULL,
    id            UUID NOT NULL UNIQUE,
    sender_id     UUID NOT NULL REFERENCES users (id),
    client_msg_id UUID NOT NULL,
    text          VARCHAR(2000) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (room_id, seq),
    UNIQUE (room_id, sender_id, client_msg_id)
);
