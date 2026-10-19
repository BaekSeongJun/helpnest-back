-- @owner BSJ
-- 발표가 끝나면 시연 계정(@example.com, 공통 비밀번호)을 막는다. 데이터는 그대로 두고 로그인만 차단.
--   psql "host=<rds> dbname=helpnest user=<user>" -v ON_ERROR_STOP=1 -f prod-demo-disable.sql
-- 다시 열려면 prod-demo-data.sql 을 실행한다 (계정을 ACTIVE 로 되돌림)

\set ON_ERROR_STOP on
\encoding UTF8
BEGIN;

UPDATE member SET status = 'INACTIVE', available = FALSE, updated_at = NOW()
 WHERE email LIKE '%@example.com';

-- 이미 로그인한 세션도 끊는다 (재발급 차단)
UPDATE refresh_token SET revoked = TRUE
 WHERE NOT revoked AND member_id IN (SELECT member_id FROM member WHERE email LIKE '%@example.com');

-- 비회원 시연 문의(guest@example.com)도 조회 비밀번호를 무효화
UPDATE ticket SET guest_password_hash = NULL WHERE guest_email = 'guest@example.com';

SELECT status, count(*) FROM member WHERE email LIKE '%@example.com' GROUP BY status;

COMMIT;
