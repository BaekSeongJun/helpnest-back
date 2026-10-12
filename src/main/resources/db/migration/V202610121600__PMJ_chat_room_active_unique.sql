-- @owner PMJ  (#116 3번) 고객당 진행 중(WAITING·OPEN) 채팅방은 하나 — 동시 요청 두 건이 방 2개를 만들지 않게 한다
-- 이미 중복이 있으면 인덱스 생성이 실패해 기동이 막히므로 먼저 정리한다:
-- 고객별로 OPEN 우선 → 오래된 방 하나만 남기고, 나머지 WAITING 은 CANCELED, OPEN 은 CLOSED
UPDATE chat_room r
   SET status = CASE WHEN r.status = 'WAITING' THEN 'CANCELED' ELSE 'CLOSED' END,
       closed_at = NOW()
 WHERE r.status IN ('WAITING', 'OPEN')
   AND EXISTS (
     SELECT 1 FROM chat_room k
      WHERE k.customer_id = r.customer_id
        AND k.status IN ('WAITING', 'OPEN')
        AND (CASE WHEN k.status = 'OPEN' THEN 0 ELSE 1 END, k.room_id)
          < (CASE WHEN r.status = 'OPEN' THEN 0 ELSE 1 END, r.room_id));

CREATE UNIQUE INDEX uq_chat_room_active_customer ON chat_room(customer_id) WHERE status IN ('WAITING', 'OPEN');
