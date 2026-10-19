-- @owner BSJ
-- 운영 시연·테스트용 데이터. smoke 데이터를 지우고 시연 계정과 한 달치 문의 데이터를 채운다.
--
-- 실행 (운영 RDS, EC2 에서):
--   psql "host=<rds> dbname=helpnest user=<user>" -v ON_ERROR_STOP=1 -f prod-demo-data.sql
--
-- - 한 트랜잭션이라 중간에 실패하면 아무것도 바뀌지 않는다
-- - 여러 번 실행해도 된다: 이전 시연 데이터(@example.com 계정의 문의·채팅·알림)를 지우고 다시 만든다
-- - LLM·SES 를 거치지 않고 DB 에 직접 넣으므로 비용이 들지 않는다 (AI 분류 결과도 미리 채운 값)
-- - 시연 계정 비밀번호는 모두 Demo1234! — 발표 후 prod-demo-disable.sql 로 막는다
-- - @example.com 은 메일이 실제로 가지 않는 예약 도메인(RFC 2606)이다

\set ON_ERROR_STOP on
-- 클라이언트 인코딩이 UTF8 이 아니면 psql 이 한글 문자열 경계를 잘못 읽는다(Windows 콘솔 등)
\encoding UTF8
BEGIN;

-- ─────────────────────────────────────────────
-- 1. 시연 계정 (있으면 비밀번호·역할·상태를 되돌린다)
-- ─────────────────────────────────────────────
INSERT INTO member (email, password, name, phone, role, status, available, email_verified_at)
SELECT v.email, '$2a$10$dxdAt0FSVKIZCCgj8pgnIeXo7aUc6d1Kl/WPvseN0wHAYaWPR/iNy', v.name, v.phone, v.role, 'ACTIVE', v.available, NOW()
FROM (VALUES
  ('admin@example.com',     '김관리', '010-1000-0001', 'ADMIN',    FALSE),
  ('lead@example.com',      '한팀장', '010-1000-0002', 'LEAD',     FALSE),
  ('agent1@example.com',    '이상담', '010-2000-0001', 'AGENT',    TRUE),
  ('agent2@example.com',    '박상담', '010-2000-0002', 'AGENT',    TRUE),
  ('agent3@example.com',    '최상담', '010-2000-0003', 'AGENT',    TRUE),
  ('customer1@example.com', '정고객', '010-3000-0001', 'CUSTOMER', FALSE),
  ('customer2@example.com', '강고객', '010-3000-0002', 'CUSTOMER', FALSE),
  ('customer3@example.com', '윤고객', '010-3000-0003', 'CUSTOMER', FALSE),
  ('customer4@example.com', '조고객', '010-3000-0004', 'CUSTOMER', FALSE),
  ('customer5@example.com', '임고객', '010-3000-0005', 'CUSTOMER', FALSE),
  ('customer6@example.com', '오고객', '010-3000-0006', 'CUSTOMER', FALSE),
  ('customer7@example.com', '서고객', '010-3000-0007', 'CUSTOMER', FALSE),
  ('customer8@example.com', '문고객', '010-3000-0008', 'CUSTOMER', FALSE)
) AS v(email, name, phone, role, available)
ON CONFLICT (email) DO UPDATE
   SET password = EXCLUDED.password, name = EXCLUDED.name, phone = EXCLUDED.phone, role = EXCLUDED.role,
       status = 'ACTIVE', available = EXCLUDED.available,
       email_verified_at = COALESCE(member.email_verified_at, NOW()), updated_at = NOW();

-- ─────────────────────────────────────────────
-- 2. 지울 대상: smoke 데이터 + 이전 시연 데이터
-- ─────────────────────────────────────────────
CREATE TEMP TABLE del_member ON COMMIT DROP AS          -- 계정까지 지움
  SELECT member_id FROM member WHERE email ILIKE 't2684258+smoke%';
CREATE TEMP TABLE demo_member ON COMMIT DROP AS         -- 계정은 두고 데이터만 지움
  SELECT member_id FROM member WHERE email LIKE '%@example.com';
CREATE TEMP TABLE del_ticket ON COMMIT DROP AS
  SELECT ticket_id FROM ticket
   WHERE title LIKE '[SMOKE]%'
      OR guest_email ILIKE 't2684258+smoke%' OR guest_email ILIKE '%@example.com'
      OR customer_id IN (SELECT member_id FROM del_member UNION ALL SELECT member_id FROM demo_member);
CREATE TEMP TABLE del_room ON COMMIT DROP AS
  SELECT room_id FROM chat_room
   WHERE ticket_id IN (SELECT ticket_id FROM del_ticket)
      OR customer_id IN (SELECT member_id FROM del_member UNION ALL SELECT member_id FROM demo_member);

\echo '== 삭제 대상 (smoke 계정 / 문의 / 채팅방)'
SELECT (SELECT count(*) FROM del_member) AS smoke_members,
       (SELECT count(*) FROM del_ticket)  AS tickets,
       (SELECT count(*) FROM del_room)    AS chat_rooms;

-- 문의에 딸린 것부터 (FK 순서)
DELETE FROM attachment  WHERE ticket_id IN (SELECT ticket_id FROM del_ticket)
                           OR reply_id IN (SELECT reply_id FROM ticket_reply WHERE ticket_id IN (SELECT ticket_id FROM del_ticket));
DELETE FROM ai_draft         WHERE ticket_id IN (SELECT ticket_id FROM del_ticket);
DELETE FROM ticket_ai_result WHERE ticket_id IN (SELECT ticket_id FROM del_ticket);
DELETE FROM mail_log         WHERE ticket_id IN (SELECT ticket_id FROM del_ticket) OR to_email ILIKE 't2684258+smoke%';
DELETE FROM notification     WHERE ticket_id IN (SELECT ticket_id FROM del_ticket)
                                OR receiver_id IN (SELECT member_id FROM del_member UNION ALL SELECT member_id FROM demo_member);
DELETE FROM survey           WHERE ticket_id IN (SELECT ticket_id FROM del_ticket);
DELETE FROM chat_message     WHERE room_id IN (SELECT room_id FROM del_room);
DELETE FROM chat_room        WHERE room_id IN (SELECT room_id FROM del_room);
DELETE FROM ticket_reply     WHERE ticket_id IN (SELECT ticket_id FROM del_ticket);
DELETE FROM ticket_history   WHERE ticket_id IN (SELECT ticket_id FROM del_ticket);
DELETE FROM password_reset_token WHERE target_type = 'GUEST_TICKET' AND target_id IN (SELECT ticket_id FROM del_ticket);
DELETE FROM ticket           WHERE ticket_id IN (SELECT ticket_id FROM del_ticket);
DELETE FROM faq      WHERE question LIKE '[SMOKE]%';
DELETE FROM template WHERE title LIKE '[SMOKE]%';

-- smoke 계정이 남긴 흔적: 지울 수 없는 행은 참조만 끊거나 시연 관리자에게 넘긴다
UPDATE ticket           SET agent_id = NULL      WHERE agent_id      IN (SELECT member_id FROM del_member);
UPDATE ticket_history   SET actor_id = NULL      WHERE actor_id      IN (SELECT member_id FROM del_member);
UPDATE ticket_reply     SET writer_id = NULL     WHERE writer_id     IN (SELECT member_id FROM del_member);
UPDATE ticket_ai_result SET overridden_by = NULL WHERE overridden_by IN (SELECT member_id FROM del_member);
UPDATE attachment       SET uploaded_by = NULL   WHERE uploaded_by   IN (SELECT member_id FROM del_member);
UPDATE chat_room        SET agent_id = NULL      WHERE agent_id      IN (SELECT member_id FROM del_member);
UPDATE faq      SET created_by = (SELECT member_id FROM member WHERE email = 'admin@example.com') WHERE created_by IN (SELECT member_id FROM del_member);
UPDATE template SET created_by = (SELECT member_id FROM member WHERE email = 'admin@example.com') WHERE created_by IN (SELECT member_id FROM del_member);
DELETE FROM ai_draft      WHERE requested_by IN (SELECT member_id FROM del_member);
DELETE FROM chat_message  WHERE sender_id    IN (SELECT member_id FROM del_member);
DELETE FROM refresh_token WHERE member_id    IN (SELECT member_id FROM del_member);
DELETE FROM member_social WHERE member_id    IN (SELECT member_id FROM del_member);
DELETE FROM password_reset_token WHERE target_type IN ('MEMBER', 'EMAIL_VERIFY') AND target_id IN (SELECT member_id FROM del_member);
DELETE FROM member        WHERE member_id    IN (SELECT member_id FROM del_member);

-- ─────────────────────────────────────────────
-- 3. FAQ·답변 템플릿 (같은 질문·제목이 없을 때만)
-- ─────────────────────────────────────────────
INSERT INTO faq (category, question, answer, created_by)
SELECT v.category, v.question, v.answer, m.member_id
FROM (VALUES
  ('DELIVERY', '주문한 상품은 언제 배송되나요?', '결제 완료 후 영업일 기준 1~2일 안에 출고되며, 출고 후 보통 1~3일 안에 받으실 수 있어요. 도서·산간 지역은 1~2일 더 걸릴 수 있습니다.'),
  ('DELIVERY', '배송 조회는 어디에서 하나요?', '마이페이지 > 주문 내역에서 송장번호를 눌러 택배사 배송 조회 화면으로 이동할 수 있어요.'),
  ('DELIVERY', '배송지를 변경하고 싶어요.', '출고 전이라면 주문 내역에서 직접 변경할 수 있어요. 이미 출고됐다면 문의하기로 송장번호와 새 주소를 남겨 주세요.'),
  ('REFUND', '환불은 얼마나 걸리나요?', '반품 상품이 입고·검수된 뒤 영업일 기준 3일 안에 결제 수단으로 환불돼요. 카드사에 따라 반영까지 3~5일이 더 걸릴 수 있습니다.'),
  ('REFUND', '부분 환불도 가능한가요?', '여러 상품을 함께 주문했다면 일부 상품만 반품·환불할 수 있어요. 할인 쿠폰은 남은 상품 기준으로 다시 계산됩니다.'),
  ('REFUND', '환불 금액이 결제 금액과 달라요.', '반품 배송비나 쿠폰 할인 재계산이 차감됐을 수 있어요. 주문 내역의 환불 상세에서 차감 내역을 확인해 주세요.'),
  ('EXCHANGE', '사이즈 교환은 어떻게 하나요?', '상품 수령 후 7일 안에 마이페이지 > 주문 내역에서 교환 신청을 해 주세요. 같은 상품의 다른 사이즈로만 교환할 수 있어요.'),
  ('EXCHANGE', '교환 배송비는 누가 부담하나요?', '단순 변심은 고객님 부담(왕복 6,000원), 상품 불량·오배송은 저희가 부담합니다.'),
  ('EXCHANGE', '받은 상품이 불량이에요.', '불량 부위 사진을 첨부해 문의하기로 접수해 주세요. 확인 후 무료로 교환 또는 환불해 드려요.'),
  ('PAYMENT', '사용 가능한 결제 수단은 무엇인가요?', '신용·체크카드, 계좌이체, 무통장입금, 간편결제(카카오페이·네이버페이·토스페이)를 사용할 수 있어요.'),
  ('PAYMENT', '결제했는데 주문 내역이 없어요.', '결제 승인 후 주문 생성까지 최대 10분이 걸릴 수 있어요. 10분이 지나도 보이지 않으면 결제 영수증을 첨부해 문의해 주세요.'),
  ('PAYMENT', '현금영수증은 어떻게 발급하나요?', '무통장입금·계좌이체 결제 시 주문서에서 신청하거나, 결제 후 마이페이지 > 주문 내역에서 발급할 수 있어요.'),
  ('ACCOUNT', '비밀번호를 잊어버렸어요.', '로그인 화면의 "비밀번호 찾기"에서 가입한 이메일을 입력하면 재설정 링크를 보내 드려요. 링크는 30분 동안 유효합니다.'),
  ('ACCOUNT', '회원 정보는 어디에서 수정하나요?', '로그인 후 내 정보 메뉴에서 이름과 연락처를 수정할 수 있어요. 이메일은 변경할 수 없습니다.'),
  ('ACCOUNT', '회원 탈퇴는 어떻게 하나요?', '내 정보 화면 아래의 "회원 탈퇴"에서 진행할 수 있어요. 탈퇴하면 같은 계정으로 다시 로그인할 수 없습니다.'),
  ('SERVICE_ERROR', '페이지가 열리지 않아요.', '브라우저 캐시를 지우거나 다른 브라우저로 접속해 보세요. 계속되면 사용 중인 기기·브라우저와 화면 캡처를 첨부해 문의해 주세요.'),
  ('SERVICE_ERROR', '결제 버튼을 눌러도 반응이 없어요.', '팝업 차단을 해제한 뒤 다시 시도해 주세요. 앱이라면 최신 버전으로 업데이트해 주세요.'),
  ('SERVICE_ERROR', '앱이 자꾸 종료돼요.', '앱을 삭제 후 다시 설치해 보세요. 문제가 계속되면 기기 모델과 OS 버전을 함께 알려 주세요.'),
  ('ETC', '고객센터 운영 시간이 궁금해요.', '평일 09:00~18:00(점심 12:00~13:00)에 운영해요. 문의하기는 24시간 접수되며 운영 시간에 순서대로 답변드립니다.'),
  ('ETC', '대량 구매(기업 구매) 문의는 어디로 하나요?', '문의하기에서 유형을 "기타"로 선택하고 수량·희망 일정을 남겨 주시면 담당자가 연락드려요.'),
  ('ETC', '비회원으로 남긴 문의는 어떻게 확인하나요?', '문의 조회 화면에서 티켓번호, 이메일, 접수 시 정한 조회 비밀번호를 입력하면 확인할 수 있어요.')
) AS v(category, question, answer)
JOIN member m ON m.email = 'admin@example.com'
WHERE NOT EXISTS (SELECT 1 FROM faq f WHERE f.question = v.question);

-- 홈의 "자주 묻는 질문"이 조회수 순이라 한 번도 안 본 FAQ 에만 조회수를 준다
UPDATE faq SET view_count = 20 + (faq_id * 37) % 180 WHERE view_count = 0;

INSERT INTO template (category, title, content, created_by)
SELECT v.category, v.title, v.content, m.member_id
FROM (VALUES
  ('DELIVERY', '배송 지연 안내', E'안녕하세요, {고객명}님. HelpNest 고객센터입니다.\n\n문의하신 주문({티켓번호})의 배송이 늦어져 불편을 드려 죄송합니다. 택배사에 확인한 결과 현재 배송 중이며, 1~2일 안에 받으실 수 있을 예정입니다.\n\n추가로 궁금하신 점이 있으면 이 문의에 답글로 남겨 주세요.'),
  ('REFUND', '환불 접수 완료 안내', E'안녕하세요, {고객명}님. HelpNest 고객센터입니다.\n\n요청하신 환불({티켓번호})이 접수되었습니다. 반품 상품 입고·검수 후 영업일 기준 3일 안에 결제 수단으로 환불되며, 카드사에 따라 반영까지 3~5일이 더 걸릴 수 있습니다.\n\n감사합니다.'),
  ('EXCHANGE', '불량 교환 접수 안내', E'안녕하세요, {고객명}님. HelpNest 고객센터입니다.\n\n불편을 드려 죄송합니다. 보내 주신 사진으로 불량을 확인했으며, 회수 기사가 1~2일 안에 방문할 예정입니다. 회수 확인 후 새 상품을 바로 보내 드리겠습니다.\n\n교환 배송비는 저희가 부담합니다.'),
  ('ACCOUNT', '비밀번호 재설정 안내', E'안녕하세요, {고객명}님. HelpNest 고객센터입니다.\n\n로그인 화면의 "비밀번호 찾기"에서 가입한 이메일을 입력하시면 재설정 링크를 보내 드립니다. 메일이 보이지 않으면 스팸함도 확인해 주세요.\n\n감사합니다.')
) AS v(category, title, content)
JOIN member m ON m.email = 'admin@example.com'
WHERE NOT EXISTS (SELECT 1 FROM template t WHERE t.title = v.title);

-- ─────────────────────────────────────────────
-- 4. 문의 72건 (최근 30일, 오늘 6건) + 이력·답변·AI 분류·설문·알림·채팅
-- ─────────────────────────────────────────────
CREATE TEMP TABLE demo_sample (
  seq INT PRIMARY KEY, category TEXT, urgency TEXT, sentiment TEXT,
  title TEXT, content TEXT, summary TEXT, reply TEXT
) ON COMMIT DROP;

INSERT INTO demo_sample VALUES
 (1,  'DELIVERY', 'HIGH', 'NEGATIVE', '주문한 지 일주일이 넘었는데 아직 안 왔어요', '지난주 월요일에 주문했는데 아직도 배송 준비 중으로만 나옵니다. 선물용이라 이번 주 안에 꼭 받아야 해요.', '배송 지연(7일 이상), 이번 주 안 수령 희망', '확인해 보니 물류센터 출고가 늦어졌습니다. 오늘 출고 처리했고 내일 중 받아보실 수 있습니다. 불편을 드려 죄송합니다.'),
 (2,  'DELIVERY', 'NORMAL', 'NEUTRAL', '배송지를 회사 주소로 바꾸고 싶어요', '어제 주문한 상품 배송지를 집에서 회사로 바꾸고 싶습니다. 회사 주소는 서울시 강남구 테헤란로 123 입니다.', '출고 전 배송지 변경 요청', '아직 출고 전이라 요청하신 회사 주소로 배송지를 변경해 두었습니다.'),
 (3,  'DELIVERY', 'LOW', 'NEUTRAL', '송장번호로 조회가 안 돼요', '문자로 받은 송장번호를 택배사 사이트에 넣었는데 조회 결과가 없다고 나와요.', '송장 조회 불가', '송장 등록 직후에는 택배사 조회까지 반나절 정도 걸릴 수 있습니다. 오늘 저녁부터 조회될 예정입니다.'),
 (4,  'REFUND', 'HIGH', 'NEGATIVE', '환불 요청한 지 2주가 지났습니다', '반품 보낸 지 2주가 넘었는데 아직 환불이 안 됐어요. 언제 처리되는지 확인 부탁드립니다.', '반품 후 2주째 환불 미처리', '반품 상품 검수가 늦어져 죄송합니다. 오늘 환불을 승인했고, 카드사 반영까지 3~5일 걸릴 수 있습니다.'),
 (5,  'REFUND', 'NORMAL', 'NEUTRAL', '부분 환불 금액 확인 부탁드립니다', '3개 중 1개만 반품했는데 환불 금액이 예상보다 적어요. 계산 내역을 알고 싶습니다.', '부분 환불 금액 문의', '쿠폰 할인이 남은 2개 상품 기준으로 다시 계산되어 차액이 생겼습니다. 주문 내역의 환불 상세에서 확인하실 수 있습니다.'),
 (6,  'REFUND', 'NORMAL', 'NEUTRAL', '반품 수거는 언제 오나요?', '반품 신청을 했는데 수거 기사님 방문 일정을 알 수 있을까요?', '반품 수거 일정 문의', '내일 오후에 수거 기사님이 방문할 예정입니다. 상품을 문 앞에 두시면 됩니다.'),
 (7,  'EXCHANGE', 'NORMAL', 'NEUTRAL', '신발 사이즈 교환하고 싶어요', '260 사이즈를 샀는데 작아서 265로 교환하고 싶습니다.', '사이즈 교환 요청(260→265)', '265 사이즈 재고를 확인해 교환 접수했습니다. 수거 후 바로 보내 드리겠습니다.'),
 (8,  'EXCHANGE', 'HIGH', 'NEGATIVE', '불량 상품이 왔어요 (화면 깨짐)', '오늘 받은 태블릿 화면 모서리가 깨져 있어요. 사진 첨부합니다. 빨리 교환해 주세요.', '수령 상품 파손, 교환 요청', '사진으로 파손을 확인했습니다. 무료 교환으로 접수했고 새 상품은 회수 당일 출고됩니다.'),
 (9,  'EXCHANGE', 'LOW', 'POSITIVE', '교환 상품 잘 받았어요, 하나만 더 여쭤볼게요', '교환해 주신 상품 잘 받았습니다. 감사해요! 혹시 기존 상품 박스는 버려도 될까요?', '교환 완료 감사, 박스 처리 문의', '잘 받으셨다니 다행입니다. 기존 박스는 버리셔도 괜찮습니다.'),
 (10, 'PAYMENT', 'URGENT', 'NEGATIVE', '결제가 두 번 됐어요', '한 번 결제했는데 카드 문자가 두 번 왔어요. 같은 금액이 두 번 빠져나갔습니다. 확인 부탁드려요.', '이중 결제 발생', '중복 승인 건을 확인해 바로 취소했습니다. 카드사에 따라 1~3일 안에 취소 내역이 반영됩니다.'),
 (11, 'PAYMENT', 'NORMAL', 'NEUTRAL', '현금영수증 발급 요청', '계좌이체로 결제했는데 현금영수증을 발급받고 싶어요. 휴대폰 번호로 부탁드립니다.', '현금영수증 발급 요청', '말씀하신 휴대폰 번호로 현금영수증을 발급해 드렸습니다.'),
 (12, 'PAYMENT', 'HIGH', 'NEUTRAL', '결제 완료됐는데 주문 내역이 없어요', '카카오페이로 결제 완료 화면까지 봤는데 주문 내역에 아무것도 없습니다.', '결제 후 주문 미생성', '결제 승인 후 주문 생성이 지연된 건을 확인해 수동으로 주문을 생성했습니다. 지금 주문 내역에서 확인하실 수 있습니다.'),
 (13, 'ACCOUNT', 'HIGH', 'NEGATIVE', '로그인이 안 되고 계정이 잠겼다고 나와요', '비밀번호를 몇 번 틀렸더니 계정이 잠겼다고 나옵니다. 급하게 주문해야 하는데 풀어 주세요.', '로그인 실패로 계정 잠김', '계정 잠금을 해제했습니다. 비밀번호가 기억나지 않으면 비밀번호 찾기로 재설정해 주세요.'),
 (14, 'ACCOUNT', 'LOW', 'NEUTRAL', '휴대폰 번호 변경은 어디서 하나요?', '번호가 바뀌어서 회원 정보의 연락처를 수정하고 싶습니다.', '연락처 변경 방법 문의', '로그인 후 내 정보 화면에서 연락처를 바로 수정하실 수 있습니다.'),
 (15, 'ACCOUNT', 'NORMAL', 'NEUTRAL', '비밀번호 재설정 메일이 안 와요', '비밀번호 찾기를 눌렀는데 30분이 지나도 메일이 안 옵니다.', '재설정 메일 미수신', '메일이 스팸함으로 분류된 것을 확인했습니다. 스팸함에서 메일을 확인해 주세요.'),
 (16, 'SERVICE_ERROR', 'URGENT', 'NEGATIVE', '결제 페이지에서 계속 오류가 나요', '결제하기를 누르면 "일시적인 오류" 화면만 나옵니다. 세 번째 시도인데 같아요.', '결제 페이지 오류 반복', '결제 대행사 일시 장애로 확인되었고 지금은 복구되었습니다. 다시 시도해 주세요. 불편을 드려 죄송합니다.'),
 (17, 'SERVICE_ERROR', 'NORMAL', 'NEUTRAL', '앱에서 장바구니가 비어 보여요', 'PC에서 담은 상품이 앱 장바구니에는 안 보입니다.', 'PC·앱 장바구니 불일치', '앱을 최신 버전으로 업데이트하면 장바구니가 동기화됩니다.'),
 (18, 'SERVICE_ERROR', 'LOW', 'NEUTRAL', '상품 이미지가 깨져서 보여요', '일부 상품 상세 페이지 이미지가 엑스박스로 나옵니다.', '상품 이미지 표시 오류', '알려 주신 상품 이미지를 다시 등록했습니다. 새로고침하면 정상으로 보입니다.'),
 (19, 'ETC', 'LOW', 'POSITIVE', '상담원분 친절한 응대 감사합니다', '지난번 교환 건 처리해 주신 상담원분께 감사 인사 전하고 싶어요.', '상담 응대 칭찬', '따뜻한 말씀 감사합니다. 담당 상담원에게 꼭 전달하겠습니다.'),
 (20, 'ETC', 'NORMAL', 'NEUTRAL', '기업 대량 구매 견적 문의', '회사 행사용으로 같은 상품 200개를 구매하려고 합니다. 견적서를 받을 수 있을까요?', '대량 구매 견적 요청(200개)', '담당 부서에 전달했습니다. 영업일 1일 안에 견적서를 메일로 보내 드리겠습니다.'),
 (21, 'ETC', 'LOW', 'NEUTRAL', '매장 픽업도 가능한가요?', '온라인으로 주문하고 가까운 매장에서 직접 받을 수 있는지 궁금합니다.', '매장 픽업 가능 여부', '현재 매장 픽업은 서울 3개 매장에서만 가능합니다. 주문서의 배송 방법에서 선택하실 수 있습니다.');

DO $$
DECLARE
  c_total CONSTANT INT := 72;
  v_agents BIGINT[];
  v_customers BIGINT[];
  n_samples INT;
  s RECORD;
  i INT;
  v_guest BOOLEAN; v_channel TEXT; v_customer BIGINT; v_agent BIGINT; v_name TEXT; v_status TEXT;
  v_created TIMESTAMPTZ; v_due TIMESTAMPTZ; v_assigned TIMESTAMPTZ; v_first TIMESTAMPTZ;
  v_resolved TIMESTAMPTZ; v_closed TIMESTAMPTZ; v_now_or_first TIMESTAMPTZ;
  v_minutes INT; v_ratio NUMERIC; v_age NUMERIC;
  v_ticket BIGINT; v_no TEXT; v_room BIGINT; v_rating INT;
BEGIN
  PERFORM setseed(0.2026);   -- 실행할 때마다 같은 모양
  SELECT array_agg(member_id ORDER BY email) INTO v_agents FROM member WHERE email ~ '^agent[0-9]+@example\.com$';
  SELECT array_agg(member_id ORDER BY email) INTO v_customers FROM member WHERE email ~ '^customer[0-9]+@example\.com$';
  SELECT count(*) INTO n_samples FROM demo_sample;

  FOR i IN 1..c_total LOOP
    SELECT * INTO s FROM demo_sample WHERE seq = (i - 1) % n_samples + 1;

    -- 접수 시각: 1~6번은 오늘, 나머지는 1~30일 전에 고르게 (KST 09~18시)
    IF i <= 6 THEN
      v_created := NOW() - make_interval(mins => i * 40 + 5);
    ELSE
      v_created := (date_trunc('day', NOW() AT TIME ZONE 'Asia/Seoul') AT TIME ZONE 'Asia/Seoul')
                   - make_interval(days => 1 + ((i - 7) * 29) / (c_total - 6))
                   + make_interval(mins => 540 + floor(random() * 540)::int);
    END IF;
    v_age := EXTRACT(EPOCH FROM NOW() - v_created) / 86400;

    -- 9건에 1건은 비회원(guest@example.com / 조회 비밀번호 Demo1234!), 6건에 1건은 채팅 상담
    v_guest := (i % 9 = 0);
    v_channel := CASE WHEN i > 6 AND NOT v_guest AND i % 6 = 0 THEN 'CHAT' ELSE 'WEB' END;
    v_customer := CASE WHEN v_guest THEN NULL ELSE v_customers[(i * 7) % array_length(v_customers, 1) + 1] END;
    v_name := COALESCE((SELECT name FROM member WHERE member_id = v_customer), '홍길동');

    -- 상태: 오늘 건은 접수·배정·처리 중·해결, 최근 3일은 처리 중/해결 반반, 그 이전은 해결(10일 넘으면 일부 종료)
    v_status := CASE
      WHEN i IN (1, 2) THEN 'RECEIVED'
      WHEN i IN (3, 4) THEN 'ASSIGNED'
      WHEN i = 5 THEN 'IN_PROGRESS'
      WHEN i = 6 THEN 'RESOLVED'
      WHEN v_channel = 'CHAT' THEN 'RESOLVED'            -- 채팅은 끝난 상담만 둔다(진행 중 방은 고객당 1개 제약)
      WHEN v_age < 3 THEN CASE WHEN i % 2 = 0 THEN 'IN_PROGRESS' ELSE 'RESOLVED' END
      WHEN i % 11 = 0 THEN 'IN_PROGRESS'
      WHEN v_age > 10 AND i % 3 = 0 THEN 'CLOSED'
      ELSE 'RESOLVED' END;

    -- 상담원 분담 40/40/20
    v_agent := CASE WHEN v_status = 'RECEIVED' THEN NULL
                    WHEN i % 5 IN (0, 1) THEN v_agents[1]
                    WHEN i % 5 IN (2, 3) THEN v_agents[2]
                    ELSE v_agents[3] END;

    SELECT response_minutes, warning_ratio INTO v_minutes, v_ratio FROM sla_policy WHERE priority = s.urgency;
    v_due := v_created + make_interval(mins => v_minutes);
    v_assigned := CASE WHEN v_agent IS NOT NULL THEN v_created + make_interval(secs => 20 + floor(random() * 100)::int) END;

    IF v_status IN ('IN_PROGRESS', 'RESOLVED', 'CLOSED') THEN
      -- 첫 응답: 대부분 5분~3시간(기한 안), 기한이 짧은 긴급·높음 중 3건에 1건은 기한을 10~60분 넘김
      v_first := v_created + make_interval(mins => CASE WHEN s.urgency IN ('URGENT', 'HIGH') AND i % 3 = 0
                                                       THEN v_minutes + 10 + floor(random() * 50)::int
                                                       ELSE LEAST((v_minutes * 0.7)::int, 5 + floor(random() * 175)::int) END);
      IF v_first > NOW() - interval '5 minutes' THEN v_first := NOW() - interval '5 minutes'; END IF;
    ELSE
      v_first := NULL;
    END IF;
    v_resolved := CASE WHEN v_status IN ('RESOLVED', 'CLOSED')
                       THEN LEAST(v_first + make_interval(mins => 20 + floor(random() * 280)::int), NOW() - interval '1 minute') END;
    v_closed := CASE WHEN v_status = 'CLOSED' THEN v_resolved + interval '3 days' END;
    v_now_or_first := COALESCE(v_first, NOW());

    v_no := 'HN-' || to_char(v_created AT TIME ZONE 'Asia/Seoul', 'YYYYMMDD') || '-' || lpad(nextval('ticket_no_seq')::text, 6, '0');

    INSERT INTO ticket (ticket_no, customer_id, guest_name, guest_email, guest_password_hash, title, content, channel,
                        category, priority, sentiment, status, agent_id, first_response_due_at, first_responded_at,
                        sla_warned, sla_breached, assigned_at, resolved_at, closed_at, created_at, updated_at)
    VALUES (v_no, v_customer,
            CASE WHEN v_guest THEN '홍길동' END,
            CASE WHEN v_guest THEN 'guest@example.com' END,
            CASE WHEN v_guest THEN '$2a$10$dxdAt0FSVKIZCCgj8pgnIeXo7aUc6d1Kl/WPvseN0wHAYaWPR/iNy' END,
            s.title, s.content, v_channel, s.category, s.urgency, s.sentiment, v_status, v_agent, v_due, v_first,
            v_now_or_first >= v_created + make_interval(secs => (v_minutes * 60 * v_ratio)::int),
            v_now_or_first > v_due,
            v_assigned, v_resolved, v_closed, v_created,
            COALESCE(v_closed, v_resolved, v_first, v_assigned, v_created))
    RETURNING ticket_id INTO v_ticket;

    -- 이력: 앱이 남기는 것과 같은 순서·값 (ASSIGN 의 to_value 는 상담원 member_id)
    INSERT INTO ticket_history (ticket_id, action, from_value, to_value, actor_id, actor_type, created_at)
    VALUES (v_ticket, 'CREATE', NULL, 'RECEIVED', v_customer, CASE WHEN v_guest THEN 'GUEST' ELSE 'MEMBER' END, v_created);
    IF s.urgency <> 'NORMAL' THEN
      INSERT INTO ticket_history (ticket_id, action, from_value, to_value, actor_type, created_at)
      VALUES (v_ticket, 'PRIORITY_CHANGE', 'NORMAL', s.urgency, 'SYSTEM', v_created + interval '3 seconds');
    END IF;
    IF v_assigned IS NOT NULL THEN
      INSERT INTO ticket_history (ticket_id, action, to_value, actor_type, created_at)
      VALUES (v_ticket, 'ASSIGN', v_agent::text, 'SYSTEM', v_assigned);
    END IF;
    IF v_first IS NOT NULL THEN
      INSERT INTO ticket_history (ticket_id, action, from_value, to_value, actor_id, actor_type, created_at)
      VALUES (v_ticket, 'STATUS_CHANGE', 'ASSIGNED', 'IN_PROGRESS', v_agent, 'MEMBER', v_first);
    END IF;
    IF v_resolved IS NOT NULL THEN
      INSERT INTO ticket_history (ticket_id, action, from_value, to_value, actor_id, actor_type, created_at)
      VALUES (v_ticket, 'STATUS_CHANGE', 'IN_PROGRESS', 'RESOLVED', v_agent, 'MEMBER', v_resolved);
    END IF;
    IF v_closed IS NOT NULL THEN
      INSERT INTO ticket_history (ticket_id, action, from_value, to_value, actor_type, created_at)
      VALUES (v_ticket, 'STATUS_CHANGE', 'RESOLVED', 'CLOSED', 'SYSTEM', v_closed);
    END IF;

    -- AI 분류 결과 (실제 호출 없이 미리 채움)
    INSERT INTO ticket_ai_result (ticket_id, category, urgency, sentiment, summary, confidence, status, model, latency_ms, created_at, updated_at)
    VALUES (v_ticket, s.category, s.urgency, s.sentiment, s.summary, round((0.72 + random() * 0.26)::numeric, 2),
            'SUCCESS', 'gemini-3.5-flash-lite', 1200 + floor(random() * 4000)::int,
            v_created + interval '3 seconds', v_created + interval '3 seconds');

    -- 답변: 상담원 첫 답변, 4건에 1건은 고객 추가 답글, 5건에 1건은 내부 메모
    IF v_first IS NOT NULL THEN
      IF i % 5 = 0 THEN
        INSERT INTO ticket_reply (ticket_id, writer_id, writer_type, content, is_internal, created_at)
        VALUES (v_ticket, v_agent, 'AGENT', '내부 메모: 담당 부서에 확인 요청함. 회신 받으면 고객 안내 예정.', TRUE, v_first - interval '1 minute');
      END IF;
      INSERT INTO ticket_reply (ticket_id, writer_id, writer_type, content, created_at)
      VALUES (v_ticket, v_agent, 'AGENT', format(E'안녕하세요, %s님. HelpNest 고객센터입니다.\n\n%s\n\n추가로 궁금하신 점이 있으면 이 문의에 답글로 남겨 주세요.', v_name, s.reply), v_first);
      IF i % 4 = 0 AND v_resolved IS NOT NULL THEN
        INSERT INTO ticket_reply (ticket_id, writer_id, writer_type, content, created_at)
        VALUES (v_ticket, v_customer, CASE WHEN v_guest THEN 'GUEST' ELSE 'CUSTOMER' END, '확인했습니다. 빠르게 처리해 주셔서 감사합니다!',
                v_first + (v_resolved - v_first) / 2);
      END IF;
    END IF;

    -- 만족도 설문: 해결된 문의마다 발송, 4건에 1건은 미응답
    IF v_resolved IS NOT NULL THEN
      v_rating := (ARRAY[5, 5, 4, 5, 3, 4, 5, 2, 4, 5, 1, 4])[i % 12 + 1];
      INSERT INTO survey (ticket_id, token, rating, comment, sent_at, expires_at, submitted_at)
      VALUES (v_ticket, encode(sha256(convert_to('demo-survey-' || v_ticket || '-' || random(), 'UTF8')), 'hex'),
              CASE WHEN i % 4 <> 3 THEN v_rating END,
              CASE WHEN i % 4 = 3 THEN NULL
                   WHEN v_rating >= 5 THEN '빠르고 친절하게 해결해 주셔서 만족합니다.'
                   WHEN v_rating = 4 THEN '해결은 잘 됐는데 답변이 조금 늦었어요.'
                   WHEN v_rating = 3 THEN NULL
                   ELSE '처리까지 너무 오래 걸렸습니다.' END,
              v_resolved, v_resolved + interval '72 hours',
              CASE WHEN i % 4 <> 3 THEN LEAST(v_resolved + make_interval(mins => 10 + floor(random() * 600)::int), NOW()) END);
    END IF;

    -- 알림 (앱과 같은 문구). 하루 넘은 배정·이틀 넘은 답변 알림은 읽음
    IF v_assigned IS NOT NULL THEN
      INSERT INTO notification (receiver_id, type, ticket_id, message, is_read, created_at)
      VALUES (v_agent, 'ASSIGNED', v_ticket, format('새 문의가 배정되었습니다. (%s) %s', v_no, s.title), v_age > 1, v_assigned);
    END IF;
    IF v_first IS NOT NULL AND v_customer IS NOT NULL THEN
      INSERT INTO notification (receiver_id, type, ticket_id, message, is_read, created_at)
      VALUES (v_customer, 'AGENT_REPLY', v_ticket, format('문의(%s)에 상담원 답변이 등록되었습니다. %s', v_no, left(s.reply, 40)), v_age > 2, v_first);
    END IF;

    -- 채팅 상담: 끝난 방 + 대화 4개
    IF v_channel = 'CHAT' THEN
      INSERT INTO chat_room (ticket_id, customer_id, agent_id, status, queued_at, opened_at, created_at, closed_at)
      VALUES (v_ticket, v_customer, v_agent, 'CLOSED', v_created - interval '1 minute', v_created, v_created - interval '1 minute', v_resolved)
      RETURNING room_id INTO v_room;
      INSERT INTO chat_message (room_id, sender_id, content, created_at) VALUES
        (v_room, v_customer, s.content, v_created + interval '30 seconds'),
        (v_room, v_agent, format('안녕하세요, %s님. %s', v_name, s.reply), v_first),
        (v_room, v_customer, '네 확인했습니다. 감사합니다!', v_first + interval '2 minutes'),
        (v_room, v_agent, '더 궁금하신 점이 있으면 언제든 문의해 주세요. 좋은 하루 보내세요.', v_first + interval '3 minutes');
    END IF;
  END LOOP;

  -- 대기 중 나간 채팅 2건 (티켓 없음)
  INSERT INTO chat_room (customer_id, status, queued_at, created_at, closed_at) VALUES
    (v_customers[2], 'CANCELED', NOW() - interval '5 days', NOW() - interval '5 days', NOW() - interval '5 days' + interval '2 minutes'),
    (v_customers[5], 'CANCELED', NOW() - interval '2 days', NOW() - interval '2 days', NOW() - interval '2 days' + interval '4 minutes');

  -- 자동 배정의 동률 판정 기준
  UPDATE member m SET last_assigned_at = (SELECT max(assigned_at) FROM ticket t WHERE t.agent_id = m.member_id)
   WHERE m.member_id = ANY (v_agents);
END $$;

\echo '== 결과'
SELECT t.status, count(*) FROM ticket t JOIN member m ON m.member_id = t.customer_id
 WHERE m.email LIKE '%@example.com' GROUP BY t.status ORDER BY t.status;
SELECT (SELECT count(*) FROM ticket WHERE guest_email = 'guest@example.com') AS guest_tickets,
       (SELECT count(*) FROM faq) AS faqs, (SELECT count(*) FROM template) AS templates,
       (SELECT count(*) FROM member WHERE email ILIKE 't2684258+smoke%') AS smoke_left;

COMMIT;
