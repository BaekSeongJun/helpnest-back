-- @owner BSJ  (docs/03 §3.1). 회원 비밀번호·비회원 조회 비밀번호 재설정 링크 토큰 (FR-AUTH-07, 09)
-- target_id 는 member_id 또는 ticket_id — 대상이 둘이라 FK 없이 참조한다
CREATE TABLE password_reset_token (
  reset_id     BIGSERIAL PRIMARY KEY,
  target_type  VARCHAR(20) NOT NULL CHECK (target_type IN ('MEMBER','GUEST_TICKET')),
  target_id    BIGINT NOT NULL,
  token_hash   VARCHAR(200) NOT NULL UNIQUE,     -- 원문 토큰은 메일 링크에만, DB엔 SHA-256 해시
  expires_at   TIMESTAMPTZ NOT NULL,             -- 발급 + 30분
  used_at      TIMESTAMPTZ,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- 재설정 성공 시 같은 대상의 남은 링크를 한꺼번에 무효화하는 조회용
CREATE INDEX idx_password_reset_target ON password_reset_token(target_type, target_id) WHERE used_at IS NULL;
