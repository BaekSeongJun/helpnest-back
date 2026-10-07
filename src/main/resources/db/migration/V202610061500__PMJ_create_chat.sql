-- @owner PMJ
-- 출처: docs/03 §3.2 (박민재 DDL 원문)에서 추출. 변경은 문서를 먼저 고친 뒤 반영한다.
-- 선행: V202609301110(ticket), BSJ V202609301000(member).

CREATE TABLE chat_room (
  room_id      BIGSERIAL PRIMARY KEY,
  ticket_id    BIGINT UNIQUE REFERENCES ticket(ticket_id),   -- WAITING/CANCELED 동안 NULL
  customer_id  BIGINT NOT NULL REFERENCES member(member_id),
  agent_id     BIGINT REFERENCES member(member_id),
  status       VARCHAR(10) NOT NULL DEFAULT 'WAITING',
  queued_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),            -- 대기 순번 = queued_at 순
  opened_at    TIMESTAMPTZ,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  closed_at    TIMESTAMPTZ
);
CREATE INDEX idx_chat_room_waiting ON chat_room(queued_at) WHERE status = 'WAITING';

CREATE TABLE chat_message (
  message_id  BIGSERIAL PRIMARY KEY,
  room_id     BIGINT NOT NULL REFERENCES chat_room(room_id),
  sender_id   BIGINT NOT NULL REFERENCES member(member_id),
  content     TEXT NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_chat_msg_room ON chat_message(room_id, created_at);
