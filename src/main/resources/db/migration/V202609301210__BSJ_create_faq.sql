-- @owner BSJ  (docs/03 §2). category 값은 ticket.category 와 같은 집합 (03 §2.1, TicketCategory)
CREATE TABLE faq (
  faq_id        BIGSERIAL PRIMARY KEY,
  category      VARCHAR(30) NOT NULL,
  question      VARCHAR(300) NOT NULL,
  answer        TEXT NOT NULL,
  is_published  BOOLEAN NOT NULL DEFAULT TRUE,
  view_count    INT NOT NULL DEFAULT 0,
  created_by    BIGINT NOT NULL REFERENCES member(member_id),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
