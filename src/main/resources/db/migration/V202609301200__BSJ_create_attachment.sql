-- @owner BSJ  (TICKET 생성 이후, docs/03 §2)
CREATE TABLE attachment (
  attachment_id  BIGINT PRIMARY KEY,                         -- 앱이 만드는 추측 불가 난수 [2^52, 2^53) (비회원 첨부 가로채기 방지)
  ticket_id      BIGINT REFERENCES ticket(ticket_id),        -- 업로드 직후엔 NULL, 티켓 생성 시 연결
  reply_id       BIGINT REFERENCES ticket_reply(reply_id),
  original_name  VARCHAR(255) NOT NULL,
  stored_key     VARCHAR(500) NOT NULL,                      -- S3 key / 로컬 경로
  content_type   VARCHAR(100) NOT NULL,
  size_bytes     BIGINT NOT NULL CHECK (size_bytes <= 10485760),
  uploaded_by    BIGINT REFERENCES member(member_id),        -- 비회원 NULL
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_attachment_orphan ON attachment(created_at) WHERE ticket_id IS NULL;   -- 고아 첨부 정리
