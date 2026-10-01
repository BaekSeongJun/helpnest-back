-- @owner BSJ  (docs/03 §2) Refresh 회전 유예: 회전으로 폐기된 시각. 로그아웃·일괄 폐기는 NULL
ALTER TABLE refresh_token ADD COLUMN rotated_at TIMESTAMPTZ;
