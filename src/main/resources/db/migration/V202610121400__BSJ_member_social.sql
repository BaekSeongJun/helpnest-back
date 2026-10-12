-- @owner BSJ  (docs/03 §3). 소셜 로그인 (FR-AUTH-11)
-- 소셜로만 가입한 회원은 비밀번호가 없다. "비밀번호 찾기"로 나중에 만들 수 있다
ALTER TABLE member ALTER COLUMN password DROP NOT NULL;

-- 한 회원이 구글·카카오를 모두 연결할 수 있어 별도 테이블. 제공자 쪽 사용자 id 가 로그인 키다(이메일은 바뀔 수 있다)
CREATE TABLE member_social (
  social_id         BIGSERIAL PRIMARY KEY,
  member_id         BIGINT      NOT NULL REFERENCES member(member_id),
  provider          VARCHAR(20) NOT NULL CHECK (provider IN ('GOOGLE','KAKAO')),
  provider_user_id  VARCHAR(100) NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (provider, provider_user_id)
);
CREATE INDEX idx_member_social_member ON member_social(member_id);
