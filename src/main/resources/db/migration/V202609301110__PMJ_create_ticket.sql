-- @owner PMJ
-- 출처: docs/03 §3.2 (박민재 DDL 원문)에서 추출. 변경은 문서를 먼저 고친 뒤 반영한다.
-- 선행: V202609301100(sla_policy), BSJ V202609301000(member).
-- NOTIFICATION·CHAT_ROOM·CHAT_MESSAGE 는 내 소유이지만 로드맵상 S2·S3 이므로 여기 포함하지 않는다.

CREATE SEQUENCE ticket_no_seq START 1;   -- 티켓번호 일련번호 (FR-INQ-03)

CREATE TABLE ticket (
  ticket_id             BIGSERIAL PRIMARY KEY,
  ticket_no             VARCHAR(20) NOT NULL UNIQUE,      -- HN-20261002-000123
                                                          -- 'HN-' || to_char(NOW() AT TIME ZONE 'Asia/Seoul','YYYYMMDD') || '-' || lpad(nextval('ticket_no_seq')::text, 6, '0')
  customer_id           BIGINT REFERENCES member(member_id),
  guest_name            VARCHAR(50),
  guest_email           VARCHAR(100),
  guest_password_hash   VARCHAR(100),
  title                 VARCHAR(200) NOT NULL,
  content               TEXT NOT NULL,
  channel               VARCHAR(10) NOT NULL DEFAULT 'WEB',
  category              VARCHAR(30) NOT NULL DEFAULT 'ETC',
  priority              VARCHAR(10) NOT NULL DEFAULT 'NORMAL' REFERENCES sla_policy(priority),
  sentiment             VARCHAR(10),
  status                VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
  agent_id              BIGINT REFERENCES member(member_id),
  first_response_due_at TIMESTAMPTZ NOT NULL,
  first_responded_at    TIMESTAMPTZ,
  sla_warned            BOOLEAN NOT NULL DEFAULT FALSE,
  sla_breached          BOOLEAN NOT NULL DEFAULT FALSE,
  assigned_at           TIMESTAMPTZ,
  resolved_at           TIMESTAMPTZ,
  closed_at             TIMESTAMPTZ,
  created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT chk_ticket_customer CHECK (customer_id IS NOT NULL OR guest_email IS NOT NULL)
);
CREATE INDEX idx_ticket_status_agent ON ticket(status, agent_id);
CREATE INDEX idx_ticket_sla ON ticket(first_response_due_at) WHERE first_responded_at IS NULL;
CREATE INDEX idx_ticket_customer ON ticket(customer_id);
CREATE INDEX idx_ticket_guest_email ON ticket(lower(guest_email));

CREATE TABLE ticket_history (
  history_id   BIGSERIAL PRIMARY KEY,
  ticket_id    BIGINT NOT NULL REFERENCES ticket(ticket_id),
  action       VARCHAR(30) NOT NULL,
  from_value   VARCHAR(50),
  to_value     VARCHAR(50),
  actor_id     BIGINT REFERENCES member(member_id),   -- SYSTEM/비회원이면 NULL
  actor_type   VARCHAR(10) NOT NULL,                  -- MEMBER / SYSTEM / GUEST
  memo         VARCHAR(500),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE ticket_reply (
  reply_id      BIGSERIAL PRIMARY KEY,
  ticket_id     BIGINT NOT NULL REFERENCES ticket(ticket_id),
  writer_id     BIGINT REFERENCES member(member_id),
  writer_type   VARCHAR(10) NOT NULL,
  content       TEXT NOT NULL,
  is_internal   BOOLEAN NOT NULL DEFAULT FALSE,       -- 내부 메모
  ai_draft_id   BIGINT,                               -- 사용한 AI 초안 (FK 없이 참조, 신수진 테이블)
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
