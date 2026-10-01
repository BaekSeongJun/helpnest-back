-- @owner BSJ
-- password_reset_token 은 FK 가 없어 S2 에 별도 마이그레이션으로 추가 (docs/03 §3)
CREATE TABLE member (
  member_id        BIGSERIAL PRIMARY KEY,
  email            VARCHAR(100) NOT NULL UNIQUE,
  password         VARCHAR(100) NOT NULL,
  name             VARCHAR(50)  NOT NULL,
  phone            VARCHAR(20),
  role             VARCHAR(20)  NOT NULL CHECK (role IN ('CUSTOMER','AGENT','LEAD','ADMIN')),
  status           VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
  available        BOOLEAN      NOT NULL DEFAULT FALSE,
  last_assigned_at TIMESTAMPTZ,
  created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE refresh_token (
  token_id    BIGSERIAL PRIMARY KEY,
  member_id   BIGINT NOT NULL REFERENCES member(member_id),
  token_hash  VARCHAR(200) NOT NULL UNIQUE,
  expires_at  TIMESTAMPTZ NOT NULL,
  revoked     BOOLEAN NOT NULL DEFAULT FALSE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refresh_token_member ON refresh_token(member_id);
