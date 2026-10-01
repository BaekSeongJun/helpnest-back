-- @owner PMJ
-- 출처: docs/03 §3.2 (박민재 DDL 원문)에서 추출. 변경은 문서를 먼저 고친 뒤 반영한다.
-- ticket.priority 가 이 테이블을 FK 로 참조하므로 ticket 마이그레이션보다 먼저 적용되어야 한다.

CREATE TABLE sla_policy (
  priority          VARCHAR(10) PRIMARY KEY,
  response_minutes  INT NOT NULL,
  warning_ratio     NUMERIC(3,2) NOT NULL DEFAULT 0.80,
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
INSERT INTO sla_policy(priority, response_minutes) VALUES
 ('URGENT',60),('HIGH',240),('NORMAL',1440),('LOW',2880);
