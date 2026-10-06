-- @owner PMJ
-- 출처: docs/03 §3.2 (박민재 DDL 원문)에서 추출. 변경은 문서를 먼저 고친 뒤 반영한다.
-- member, ticket 을 FK 로 참조하므로 두 테이블 마이그레이션보다 뒤에 적용되어야 한다(docs/03 §3.2 생성 순서 ④).

CREATE TABLE notification (
  notification_id BIGSERIAL PRIMARY KEY,
  receiver_id     BIGINT NOT NULL REFERENCES member(member_id),
  type            VARCHAR(30) NOT NULL,
  ticket_id       BIGINT REFERENCES ticket(ticket_id),
  message         VARCHAR(300) NOT NULL,
  is_read         BOOLEAN NOT NULL DEFAULT FALSE,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- 알림 벨의 두 쿼리(수신자별 목록, 미읽음 수)가 모두 이 두 컬럼으로 거르므로 복합 인덱스로 둔다
CREATE INDEX idx_noti_receiver ON notification(receiver_id, is_read);
