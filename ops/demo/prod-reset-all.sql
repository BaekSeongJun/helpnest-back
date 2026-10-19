-- @owner BSJ
-- 운영 DB 의 데이터를 전부 비운다 (시연 데이터를 넣기 전 1회). 계정·문의·채팅·FAQ·템플릿 모두 삭제.
--   psql "host=<rds> dbname=helpnest user=<user>" -v ON_ERROR_STOP=1 -f prod-reset-all.sql
--   이어서 prod-demo-data.sql 실행
--
-- 남기는 것 (지우면 앱이 깨진다)
--   flyway_schema_history  지우면 다음 배포 때 마이그레이션을 처음부터 다시 돌려 기동 실패
--   sla_policy             ticket.priority 의 FK 대상이자 설정값
-- 실행 전에 RDS 스냅샷을 떠 둔다. 되돌릴 방법은 스냅샷뿐이다.

\set ON_ERROR_STOP on
\encoding UTF8
BEGIN;

DO $$
DECLARE
  v_tables TEXT;
BEGIN
  -- 새 테이블이 생겨도 빠지지 않도록 현재 스키마의 테이블 목록에서 고른다
  SELECT string_agg(format('%I', tablename), ', ' ORDER BY tablename) INTO v_tables
    FROM pg_tables
   WHERE schemaname = current_schema()
     AND tablename NOT IN ('flyway_schema_history', 'sla_policy');
  RAISE NOTICE '비우는 테이블: %', v_tables;
  EXECUTE 'TRUNCATE ' || v_tables || ' RESTART IDENTITY';
END $$;

-- 티켓번호 일련번호는 컬럼 소유 시퀀스가 아니라 RESTART IDENTITY 로 초기화되지 않는다
ALTER SEQUENCE ticket_no_seq RESTART WITH 1;

SELECT (SELECT count(*) FROM member) AS members, (SELECT count(*) FROM ticket) AS tickets,
       (SELECT count(*) FROM sla_policy) AS sla_policy_kept,
       (SELECT count(*) FROM flyway_schema_history) AS flyway_kept;

COMMIT;
