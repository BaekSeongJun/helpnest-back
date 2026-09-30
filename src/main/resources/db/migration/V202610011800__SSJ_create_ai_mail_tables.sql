-- @owner SSJ — docs/03 §3.3 (생성 순서 ⑤: MEMBER·TICKET 이후)
CREATE TABLE ticket_ai_result (
  ai_result_id  BIGSERIAL PRIMARY KEY,
  ticket_id     BIGINT NOT NULL UNIQUE REFERENCES ticket(ticket_id),
  category      VARCHAR(30),
  urgency       VARCHAR(10),
  sentiment     VARCHAR(10),
  summary       VARCHAR(500),
  confidence    NUMERIC(3,2),
  status        VARCHAR(10) NOT NULL,          -- SUCCESS / FAILED
  model         VARCHAR(100),
  raw_response  JSONB,
  latency_ms    INT,
  overridden_by BIGINT REFERENCES member(member_id), -- 상담원 수동 수정
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE ai_draft (
  draft_id       BIGSERIAL PRIMARY KEY,
  ticket_id      BIGINT NOT NULL REFERENCES ticket(ticket_id),
  requested_by   BIGINT NOT NULL REFERENCES member(member_id),
  content        TEXT NOT NULL,
  reference_refs JSONB,                        -- [{"type":"FAQ","id":3},{"type":"REPLY","id":41}]
  model          VARCHAR(100),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE mail_log (
  mail_id     BIGSERIAL PRIMARY KEY,
  ticket_id   BIGINT REFERENCES ticket(ticket_id),
  to_email    VARCHAR(100) NOT NULL,
  mail_type   VARCHAR(30) NOT NULL,            -- RESOLVED_SURVEY / AGENT_REPLY / PASSWORD_RESET / GUEST_PASSWORD_RESET
  status      VARCHAR(10) NOT NULL,            -- SENT / FAILED / LOGGED(local)
  retry_count INT NOT NULL DEFAULT 0,
  error_msg   VARCHAR(500),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  sent_at     TIMESTAMPTZ
);
