-- @owner BSJ  (docs/03 §3.1). 해결 후 만족도 설문 (FR-SRV-01~06)
-- 재해결 시 같은 행을 재발급한다: token·sent_at·expires_at 갱신 (ticket_id UNIQUE 유지)
CREATE TABLE survey (
  survey_id     BIGSERIAL PRIMARY KEY,
  ticket_id     BIGINT NOT NULL UNIQUE REFERENCES ticket(ticket_id),
  token         VARCHAR(64) NOT NULL UNIQUE,
  rating        SMALLINT CHECK (rating BETWEEN 1 AND 5),
  comment       VARCHAR(1000),
  sent_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expires_at    TIMESTAMPTZ NOT NULL,           -- sent_at + 72h (재문의 시 NOW()로 만료)
  submitted_at  TIMESTAMPTZ
);
