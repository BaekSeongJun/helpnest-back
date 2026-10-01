-- @owner BSJ  (docs/03 §2). category 값은 ticket.category 와 같은 집합 (03 §2.1, TicketCategory)
-- content 의 {고객명}·{티켓번호} 는 프론트 TemplatePicker 가 삽입할 때 치환한다
CREATE TABLE template (
  template_id   BIGSERIAL PRIMARY KEY,
  category      VARCHAR(30) NOT NULL,
  title         VARCHAR(100) NOT NULL,
  content       TEXT NOT NULL,
  is_active     BOOLEAN NOT NULL DEFAULT TRUE,
  created_by    BIGINT NOT NULL REFERENCES member(member_id),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
