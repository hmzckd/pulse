CREATE TABLE users (
    id         UUID PRIMARY KEY,
    username   VARCHAR(20) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE rooms (
    id         UUID PRIMARY KEY,
    name       VARCHAR(50) NOT NULL,
    created_by UUID NOT NULL REFERENCES users (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seq   BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE room_members (
    room_id       UUID NOT NULL REFERENCES rooms (id),
    user_id       UUID NOT NULL REFERENCES users (id),
    joined_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_read_seq BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (room_id, user_id)
);

CREATE INDEX room_members_user_id_idx ON room_members (user_id);
