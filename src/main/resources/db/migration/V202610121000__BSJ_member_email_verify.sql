-- @owner BSJ  (docs/03 §3). 이메일 인증 가입 접점 (back #117)
-- PENDING = 가입했지만 인증 링크를 아직 누르지 않은 회원. 로그인할 수 없다
ALTER TABLE member ADD COLUMN email_verified_at TIMESTAMPTZ;   -- 기존 회원은 NULL (재인증 요구 없음)
ALTER TABLE member DROP CONSTRAINT member_status_check;
ALTER TABLE member ADD CONSTRAINT member_status_check CHECK (status IN ('ACTIVE','INACTIVE','PENDING'));

-- 인증 링크도 같은 1회용 토큰 테이블을 쓴다. EMAIL_VERIFY → target_id 는 member_id
ALTER TABLE password_reset_token DROP CONSTRAINT password_reset_token_target_type_check;
ALTER TABLE password_reset_token ADD CONSTRAINT password_reset_token_target_type_check
  CHECK (target_type IN ('MEMBER','GUEST_TICKET','EMAIL_VERIFY'));
