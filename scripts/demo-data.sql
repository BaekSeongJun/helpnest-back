-- @owner BSJ
-- 발표용 목 데이터 (S4-5). 상담원 4명의 성향을 다르게 두어 대시보드·상담원 상세·리포트·설문 화면이 비교되도록 만든다.
--
-- 실행 (psql, 운영은 EC2 에서 RDS 로):
--   psql "<접속 문자열>" -v ON_ERROR_STOP=1 -v demo_pw='<시연 계정 비밀번호>' -f scripts/demo-data.sql
--
-- - 시각은 모두 now() 기준 상대값(최근 40일)이라 발표 직전에 다시 실행하면 그날 기준으로 다시 만들어진다
-- - 다시 실행해도 안전: 1단계에서 이전 데모 데이터(@demo.helpnest.kro.kr 회원·티켓, 데모 상담원에 배정된 티켓)를 지우고 새로 넣는다
-- - 비밀번호는 커밋하지 않는다(public repo). 데모 회원 전원이 같은 비밀번호, BCrypt 는 pgcrypto 로 만든다($2a$, Spring 호환)
-- - 메일·알림 이벤트는 발생하지 않는다(SQL 직접 입력). 단 시연 중 데모 고객 티켓을 해결하면 @demo.helpnest.kro.kr 로 메일이
--   나가 반송된다 → 실시간 시연은 실제 받을 수 있는 이메일로 새 문의를 넣어서 한다
-- - FAQ·템플릿은 같은 질문/제목이 없을 때만 넣는다(운영에 이미 있으면 그대로 둔다)

\set ON_ERROR_STOP on
\if :{?demo_pw}
\else
  \echo 'demo_pw 가 필요합니다: -v demo_pw=...'
  \quit
\endif

BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;
-- \gset 으로 받아 해시를 화면에 찍지 않는다. 같은 날 다시 돌리면 같은 모양(setseed)
SELECT set_config('demo.pw_hash', crypt(:'demo_pw', gen_salt('bf', 10)), true) AS demo_hash_set \gset
SELECT setseed(0.42) AS demo_seed \gset

-- ─── 1. 이전 데모 데이터 정리 ─────────────────────────────────────────────
CREATE TEMP TABLE demo_m ON COMMIT DROP AS
  SELECT member_id FROM member WHERE email LIKE '%@demo.helpnest.kro.kr';
CREATE TEMP TABLE demo_t ON COMMIT DROP AS
  SELECT ticket_id FROM ticket
  WHERE customer_id IN (SELECT member_id FROM demo_m) OR agent_id IN (SELECT member_id FROM demo_m)
     OR guest_email LIKE '%@demo.helpnest.kro.kr';
CREATE TEMP TABLE demo_r ON COMMIT DROP AS
  SELECT room_id FROM chat_room
  WHERE customer_id IN (SELECT member_id FROM demo_m) OR agent_id IN (SELECT member_id FROM demo_m)
     OR ticket_id IN (SELECT ticket_id FROM demo_t);

DELETE FROM chat_message WHERE room_id IN (SELECT room_id FROM demo_r);
DELETE FROM chat_room WHERE room_id IN (SELECT room_id FROM demo_r);
DELETE FROM notification WHERE receiver_id IN (SELECT member_id FROM demo_m) OR ticket_id IN (SELECT ticket_id FROM demo_t);
DELETE FROM survey WHERE ticket_id IN (SELECT ticket_id FROM demo_t);
DELETE FROM ticket_ai_result WHERE ticket_id IN (SELECT ticket_id FROM demo_t);
UPDATE ticket_ai_result SET overridden_by = NULL WHERE overridden_by IN (SELECT member_id FROM demo_m);
DELETE FROM ai_draft WHERE ticket_id IN (SELECT ticket_id FROM demo_t) OR requested_by IN (SELECT member_id FROM demo_m);
DELETE FROM mail_log WHERE ticket_id IN (SELECT ticket_id FROM demo_t);
DELETE FROM attachment WHERE ticket_id IN (SELECT ticket_id FROM demo_t)
   OR reply_id IN (SELECT reply_id FROM ticket_reply WHERE ticket_id IN (SELECT ticket_id FROM demo_t))
   OR uploaded_by IN (SELECT member_id FROM demo_m);
DELETE FROM ticket_reply WHERE ticket_id IN (SELECT ticket_id FROM demo_t) OR writer_id IN (SELECT member_id FROM demo_m);
DELETE FROM ticket_history WHERE ticket_id IN (SELECT ticket_id FROM demo_t) OR actor_id IN (SELECT member_id FROM demo_m);
DELETE FROM ticket WHERE ticket_id IN (SELECT ticket_id FROM demo_t);
DELETE FROM faq WHERE created_by IN (SELECT member_id FROM demo_m);
DELETE FROM template WHERE created_by IN (SELECT member_id FROM demo_m);
DELETE FROM refresh_token WHERE member_id IN (SELECT member_id FROM demo_m);
DELETE FROM password_reset_token WHERE target_type = 'MEMBER' AND target_id IN (SELECT member_id FROM demo_m);
DELETE FROM member WHERE member_id IN (SELECT member_id FROM demo_m);

-- ─── 2. 회원 ─────────────────────────────────────────────────────────────
-- 상담원 성향(fr = SLA 대비 첫 응답 비율, rh = 해결까지 추가 시간 중앙값, rating = 평균 별점, w = 배정 가중치)
CREATE TEMP TABLE demo_agent (email text, name text, fr numeric, rh numeric, rating numeric, w int, available boolean) ON COMMIT DROP;
INSERT INTO demo_agent VALUES
  ('agent.seoyeon@demo.helpnest.kro.kr', '이서연', 0.20,  3, 4.7, 30, true),   -- 에이스: 빠르고 만족도 높음
  ('agent.junho@demo.helpnest.kro.kr',   '박준호', 0.45,  8, 4.2, 28, true),   -- 평균
  ('agent.haneul@demo.helpnest.kro.kr',  '정하늘', 0.30,  5, 3.9, 22, true),   -- 빠르지만 만족도 보통
  ('agent.minji@demo.helpnest.kro.kr',   '최민지', 0.85, 14, 3.5, 20, false);  -- 신입: 느리고 SLA 위반 많음 (상담 OFF)

INSERT INTO member (email, password, name, phone, role, status, available, created_at)
SELECT email, current_setting('demo.pw_hash'), name, phone, role, 'ACTIVE', available, now() - interval '60 days'
FROM (VALUES
  ('admin@demo.helpnest.kro.kr', '한관리', '010-1000-0001', 'ADMIN', false),
  ('lead@demo.helpnest.kro.kr',  '김도윤', '010-1000-0002', 'LEAD',  false)
) v(email, name, phone, role, available)
UNION ALL
SELECT email, current_setting('demo.pw_hash'), name, NULL, 'AGENT', 'ACTIVE', available, now() - interval '60 days' FROM demo_agent;

INSERT INTO member (email, password, name, phone, role, created_at)
SELECT 'customer' || lpad(i::text, 2, '0') || '@demo.helpnest.kro.kr', current_setting('demo.pw_hash'), n,
       '010-' || (2000 + i) || '-' || (3000 + i * 7), 'CUSTOMER', now() - interval '55 days' + i * interval '2 days'
FROM unnest(ARRAY['김민수','이지은','박서준','최유나','정도현','강하린','윤재원','장수빈','임채윤','한지호','오세린','서태민'])
     WITH ORDINALITY AS c(n, i);

-- ─── 3. FAQ·템플릿 (없을 때만) ────────────────────────────────────────────
INSERT INTO faq (category, question, answer, view_count, created_by)
SELECT v.category, v.question, v.answer, (random() * 300)::int, (SELECT member_id FROM member WHERE email = 'lead@demo.helpnest.kro.kr')
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
  ('ACCOUNT', '회원 탈퇴는 어떻게 하나요?', '문의하기로 탈퇴를 요청해 주세요. 진행 중인 주문·문의가 있으면 처리 완료 후 탈퇴할 수 있어요.'),
  ('SERVICE_ERROR', '페이지가 열리지 않아요.', '브라우저 캐시를 지우거나 다른 브라우저로 접속해 보세요. 계속되면 사용 중인 기기·브라우저와 화면 캡처를 첨부해 문의해 주세요.'),
  ('SERVICE_ERROR', '결제 버튼을 눌러도 반응이 없어요.', '팝업 차단을 해제한 뒤 다시 시도해 주세요. 앱이라면 최신 버전으로 업데이트해 주세요.'),
  ('SERVICE_ERROR', '앱이 자꾸 종료돼요.', '앱을 삭제 후 다시 설치해 보세요. 문제가 계속되면 기기 모델과 OS 버전을 함께 알려 주세요.'),
  ('ETC', '고객센터 운영 시간이 궁금해요.', '평일 09:00~18:00(점심 12:00~13:00)에 운영해요. 문의하기는 24시간 접수되며 운영 시간에 순서대로 답변드립니다.'),
  ('ETC', '대량 구매(기업 구매) 문의는 어디로 하나요?', '문의하기에서 유형을 "기타"로 선택하고 수량·희망 일정을 남겨 주시면 담당자가 연락드려요.'),
  ('ETC', '비회원으로 남긴 문의는 어떻게 확인하나요?', '문의 조회 화면에서 티켓번호, 이메일, 접수 시 정한 조회 비밀번호를 입력하면 확인할 수 있어요.')
) AS v(category, question, answer)
WHERE NOT EXISTS (SELECT 1 FROM faq f WHERE f.question = v.question);

INSERT INTO template (category, title, content, created_by)
SELECT v.category, v.title, v.content, (SELECT member_id FROM member WHERE email = 'lead@demo.helpnest.kro.kr')
FROM (VALUES
  ('DELIVERY', '배송 지연 안내', E'안녕하세요, {고객명}님. HelpNest 고객센터입니다.\n\n문의하신 주문({티켓번호})의 배송이 늦어져 불편을 드려 죄송합니다. 택배사에 확인한 결과 현재 배송 중이며, 1~2일 안에 받으실 수 있을 예정입니다.\n\n추가로 궁금하신 점이 있으면 이 문의에 답글로 남겨 주세요.'),
  ('REFUND', '환불 접수 완료 안내', E'안녕하세요, {고객명}님. HelpNest 고객센터입니다.\n\n요청하신 환불({티켓번호})이 접수되었습니다. 반품 상품 입고·검수 후 영업일 기준 3일 안에 결제 수단으로 환불되며, 카드사에 따라 반영까지 3~5일이 더 걸릴 수 있습니다.\n\n감사합니다.'),
  ('EXCHANGE', '교환 회수 안내', E'안녕하세요, {고객명}님. HelpNest 고객센터입니다.\n\n교환 요청({티켓번호})이 접수되어 1~2일 안에 택배 기사님이 회수 방문 예정입니다. 회수 확인 후 새 상품을 바로 출고해 드릴게요.\n\n감사합니다.'),
  ('SERVICE_ERROR', '오류 확인 중 안내', E'안녕하세요, {고객명}님. HelpNest 고객센터입니다.\n\n말씀하신 오류({티켓번호})를 개발팀에 전달해 확인하고 있습니다. 원인이 확인되는 대로 이 문의로 다시 안내드리겠습니다.\n\n불편을 드려 죄송합니다.')
) AS v(category, title, content)
WHERE NOT EXISTS (SELECT 1 FROM template t WHERE t.title = v.title);

-- ─── 4. 티켓 + 이력·답변·AI 결과·설문 ─────────────────────────────────────
DO $$
#variable_conflict use_column
DECLARE
  n_tickets CONSTANT int := 180;
  cats  text[] := ARRAY['DELIVERY','REFUND','EXCHANGE','PAYMENT','ACCOUNT','SERVICE_ERROR','ETC'];
  cat_w int[]  := ARRAY[25, 18, 14, 13, 10, 12, 8];
  -- 유형별 (제목, 본문) 4쌍 — 같은 인덱스끼리 짝
  titles jsonb := '{
    "DELIVERY": ["주문한 지 5일째인데 아직 배송 전이에요", "배송 조회가 일주일째 그대로예요", "다른 주소로 배송됐다고 나와요", "배송 예정일이 계속 미뤄져요"],
    "REFUND": ["환불 신청했는데 아직 입금이 안 됐어요", "환불 금액이 결제 금액보다 적어요", "부분 환불 가능한가요?", "반품 회수 후 환불이 안 돼요"],
    "EXCHANGE": ["사이즈가 맞지 않아 교환하고 싶어요", "받은 상품이 불량이에요", "색상이 사진과 달라요", "교환 신청 버튼이 안 보여요"],
    "PAYMENT": ["결제는 됐는데 주문 내역이 없어요", "카드 결제가 두 번 됐어요", "현금영수증 발급이 안 돼요", "쿠폰이 적용되지 않았어요"],
    "ACCOUNT": ["비밀번호 재설정 메일이 안 와요", "로그인이 계속 풀려요", "회원 정보 수정이 안 돼요", "탈퇴하고 싶어요"],
    "SERVICE_ERROR": ["결제 페이지에서 오류가 나요", "앱이 실행하자마자 꺼져요", "장바구니에 담은 상품이 사라졌어요", "상품 상세 페이지가 안 열려요"],
    "ETC": ["매장 방문 수령 가능한가요?", "기업 대량 구매 문의드립니다", "선물 포장 되나요?", "재입고 알림 신청은 어떻게 하나요?"]
  }';
  bodies jsonb := '{
    "DELIVERY": ["지난주 월요일에 주문했는데 아직 상품 준비 중으로 나옵니다. 언제 출고되나요?", "송장번호는 나왔는데 택배사 조회에서 집하 이후로 변화가 없어요. 확인 부탁드립니다.", "배송 완료라고 나오는데 받은 적이 없어요. 주소를 확인해 보니 예전 주소로 간 것 같습니다.", "처음엔 화요일 도착이라더니 목요일, 다시 다음 주로 바뀌었어요. 정확한 일정을 알고 싶습니다."],
    "REFUND": ["반품 접수하고 상품도 회수됐는데 일주일째 환불이 안 들어왔어요.", "5만 원 결제했는데 4만 2천 원만 환불됐어요. 차감 내역을 알려 주세요.", "세 개 주문했는데 하나만 반품하고 싶어요. 부분 환불 되나요?", "택배 기사님이 회수해 가신 지 5일 지났는데 환불 진행 상태가 그대로예요."],
    "EXCHANGE": ["M 사이즈를 받았는데 작아서 L로 바꾸고 싶어요.", "박스를 열어 보니 상품 모서리가 깨져 있었어요. 사진 첨부합니다.", "상세 페이지에서는 베이지였는데 받아 보니 거의 회색이에요.", "주문 내역에 교환 신청 버튼이 없어서 문의드려요."],
    "PAYMENT": ["카드 승인 문자는 왔는데 마이페이지에 주문이 없어요.", "같은 금액이 카드에서 두 번 승인됐어요. 한 건 취소해 주세요.", "계좌이체로 결제했는데 현금영수증 발급 버튼이 비활성화돼 있어요.", "10% 할인 쿠폰을 선택했는데 결제 금액에 반영이 안 됐어요."],
    "ACCOUNT": ["비밀번호 찾기를 세 번 눌렀는데 메일이 하나도 안 와요. 스팸함도 확인했어요.", "앱에서 로그인해도 몇 분 지나면 다시 로그인하라고 나와요.", "연락처를 바꾸려는데 저장 버튼을 눌러도 반영이 안 됩니다.", "더 이상 이용하지 않아서 탈퇴하려고 합니다. 절차 안내 부탁드려요."],
    "SERVICE_ERROR": ["결제하기를 누르면 일시적인 오류라는 메시지만 나와요. 세 번째 시도 중입니다.", "어제 업데이트 후 앱을 켜면 바로 종료돼요. 갤럭시 S23 사용 중입니다.", "어제 담아 둔 상품이 오늘 보니 장바구니에서 다 사라졌어요.", "특정 상품을 누르면 흰 화면만 나오고 아무것도 안 떠요."],
    "ETC": ["근처 매장에서 직접 받을 수 있는지 궁금합니다.", "회사 행사용으로 200개 정도 구매하려고 합니다. 견적 받을 수 있을까요?", "선물용인데 포장 옵션이 있나요?", "품절 상품 재입고 알림을 받고 싶은데 메뉴를 못 찾겠어요."]
  }';
  answers jsonb := '{
    "DELIVERY": "확인해 보니 물류센터 출고가 하루 지연되었습니다. 오늘 출고 처리되어 1~2일 안에 받으실 수 있습니다. 불편을 드려 죄송합니다.",
    "REFUND": "환불 내역을 확인했습니다. 카드사 승인 취소는 완료되었고 카드사에 따라 3~5영업일 안에 반영됩니다. 차감 내역은 주문 상세에서 확인하실 수 있습니다.",
    "EXCHANGE": "교환 접수 도와드렸습니다. 1~2일 안에 회수 기사님이 방문하시고, 회수 확인 후 새 상품을 바로 출고해 드리겠습니다.",
    "PAYMENT": "결제 내역을 확인했습니다. 중복 승인 건은 취소 처리했고, 주문은 정상 생성되어 마이페이지에서 확인하실 수 있습니다.",
    "ACCOUNT": "계정 상태를 확인했습니다. 메일 수신 설정을 초기화했으니 다시 시도해 주세요. 계속 문제가 있으면 이 문의에 답글 남겨 주세요.",
    "SERVICE_ERROR": "말씀하신 오류를 개발팀과 확인해 수정 배포를 완료했습니다. 앱을 최신 버전으로 업데이트한 뒤 다시 이용해 주세요. 불편을 드려 죄송합니다.",
    "ETC": "문의 주셔서 감사합니다. 요청하신 내용은 담당 부서에 전달했으며, 영업일 기준 1일 안에 이메일로 상세 안내드리겠습니다."
  }';
  follow_ups text[] := ARRAY['답변 감사합니다. 혹시 언제쯤 처리되는지 조금 더 정확히 알 수 있을까요?',
                             '확인했는데 아직 그대로예요. 다시 한 번 봐 주세요.',
                             '추가로 한 가지만 더 여쭤봐도 될까요? 같은 주문의 다른 상품도 확인 부탁드려요.'];
  memos text[] := ARRAY['물류센터에 출고 지연 사유 확인 요청함', '카드사 취소 내역 캡처 받아 둠', '동일 고객 재문의 건 — 우선 처리', '개발팀 이슈로 전달함'];
  comments_good text[] := ARRAY['빠르게 처리해 주셔서 감사합니다!', '친절한 안내 감사해요.', '덕분에 금방 해결됐어요.', '설명이 자세해서 좋았습니다.', NULL, NULL];
  comments_mid  text[] := ARRAY['해결은 됐는데 답변이 조금 늦었어요.', '괜찮았습니다.', NULL, NULL];
  comments_bad  text[] := ARRAY['답변까지 너무 오래 걸렸어요.', '같은 내용을 여러 번 설명해야 했어요.', '처리는 됐지만 안내가 부족했습니다.'];
  guest_names text[] := ARRAY['유민호','배소연','신동훈','문가영','권태윤','홍예린','노승우','양지민'];
  prio_order text[] := ARRAY['LOW','NORMAL','HIGH','URGENT'];

  agents record;
  a_ids bigint[]; a_fr numeric[]; a_rh numeric[]; a_rating numeric[]; a_w int[]; a_total int;
  cust_ids bigint[]; cust_names text[];
  i int; k int; pick int; acc int; r numeric;
  cat text; idx int; title text; body text; sentiment text; base_prio text; prio text; sla_min int;
  created timestamptz; due timestamptz; assigned timestamptz; fr_at timestamptz; resolved timestamptz; closed timestamptz;
  status text; breached boolean; warned boolean; agent_idx int; agent_id bigint;
  customer_id bigint; customer_name text; guest_email text; guest_name text; ticket_no text; tid bigint;
  fr_delay_min numeric; rating int; submitted timestamptz; day_off int; vip int;
BEGIN
  SELECT array_agg(m.member_id ORDER BY a.w DESC), array_agg(a.fr ORDER BY a.w DESC), array_agg(a.rh ORDER BY a.w DESC),
         array_agg(a.rating ORDER BY a.w DESC), array_agg(a.w ORDER BY a.w DESC), sum(a.w)
    INTO a_ids, a_fr, a_rh, a_rating, a_w, a_total
  FROM demo_agent a JOIN member m ON m.email = a.email;
  SELECT array_agg(member_id ORDER BY email), array_agg(name ORDER BY email) INTO cust_ids, cust_names
  FROM member WHERE role = 'CUSTOMER' AND email LIKE '%@demo.helpnest.kro.kr';

  FOR i IN 1..n_tickets LOOP
    -- 접수 시각: 최근일수록 많게(0~40일 전), 서울 08~22시
    day_off := floor(power(random(), 1.4) * 40)::int;
    created := ((now() AT TIME ZONE 'Asia/Seoul')::date - day_off + make_interval(hours => 8 + floor(random() * 14)::int,
                mins => floor(random() * 60)::int)) AT TIME ZONE 'Asia/Seoul';
    IF created > now() - interval '5 minutes' THEN
      created := now() - make_interval(mins => 5 + floor(random() * 180)::int);
    END IF;
    IF i > n_tickets - 3 THEN  -- 마지막 3건은 방금 들어온 미배정 (티켓함 '미배정' 탭 시연용)
      created := now() - make_interval(mins => 3 + floor(random() * 25)::int);
    END IF;

    -- 유형 (가중치)
    pick := floor(random() * 100)::int; acc := 0;
    FOR k IN 1..array_length(cats, 1) LOOP
      acc := acc + cat_w[k];
      IF pick < acc THEN cat := cats[k]; EXIT; END IF;
    END LOOP;
    idx := floor(random() * 4)::int;
    title := titles -> cat ->> idx;
    body := bodies -> cat ->> idx;

    -- 감정·우선순위 (불만이면 한 단계 상향, FR-AI-02)
    r := random();
    sentiment := CASE WHEN r < CASE WHEN cat IN ('SERVICE_ERROR','REFUND','DELIVERY') THEN 0.28 ELSE 0.12 END THEN 'NEGATIVE'
                      WHEN r > 0.85 THEN 'POSITIVE' ELSE 'NEUTRAL' END;
    r := random();
    base_prio := CASE WHEN r < 0.05 THEN 'URGENT'
                      WHEN r < CASE WHEN cat IN ('SERVICE_ERROR','PAYMENT') THEN 0.45 ELSE 0.20 END THEN 'HIGH'
                      WHEN r > 0.85 THEN 'LOW' ELSE 'NORMAL' END;
    prio := base_prio;
    IF sentiment = 'NEGATIVE' AND base_prio <> 'URGENT' THEN
      prio := prio_order[array_position(prio_order, base_prio) + 1];
    END IF;
    SELECT response_minutes INTO sla_min FROM sla_policy WHERE priority = prio;
    due := created + make_interval(mins => sla_min);

    -- 고객: 회원 65%(앞쪽 3명은 단골이라 더 자주), 비회원 35%
    customer_id := NULL; guest_email := NULL; guest_name := NULL;
    IF random() < 0.65 THEN
      vip := CASE WHEN random() < 0.35 THEN 1 + floor(random() * 3)::int ELSE 1 + floor(random() * array_length(cust_ids, 1))::int END;
      customer_id := cust_ids[vip]; customer_name := cust_names[vip];
    ELSE
      k := 1 + floor(random() * array_length(guest_names, 1))::int;
      guest_name := guest_names[k]; customer_name := guest_name;
      guest_email := 'guest' || k || '@demo.helpnest.kro.kr';
    END IF;

    -- 상담원 (가중치)
    pick := floor(random() * a_total)::int; acc := 0;
    FOR k IN 1..array_length(a_ids, 1) LOOP
      acc := acc + a_w[k];
      IF pick < acc THEN agent_idx := k; EXIT; END IF;
    END LOOP;

    -- 진행 상태: 방금 들어온 일부는 미배정, 나머지는 상담원 성향대로 응답·해결
    assigned := NULL; fr_at := NULL; resolved := NULL; closed := NULL; agent_id := NULL;
    IF i > n_tickets - 3 OR (now() - created < interval '40 minutes' AND random() < 0.6) THEN
      status := 'RECEIVED';
    ELSE
      agent_id := a_ids[agent_idx];
      assigned := created + make_interval(secs => 40 + floor(random() * 140)::int);
      fr_delay_min := sla_min * a_fr[agent_idx] * (0.4 + random() * 1.3)
                      * CASE WHEN random() < 0.06 THEN 2.5 + random() * 2 ELSE 1 END;  -- 가끔 몰리는 날: 누구나 SLA 를 넘길 수 있게
      fr_at := created + make_interval(secs => (fr_delay_min * 60)::int);
      IF fr_at > now() THEN
        fr_at := NULL; status := 'ASSIGNED';
      ELSE
        resolved := fr_at + make_interval(mins => (a_rh[agent_idx] * 60 * (0.3 + random() * 1.4))::int);
        IF resolved > now() THEN
          resolved := NULL; status := 'IN_PROGRESS';
        ELSE
          status := 'RESOLVED';
        END IF;
      END IF;
    END IF;
    breached := (fr_at IS NOT NULL AND fr_at > due) OR (fr_at IS NULL AND status <> 'RECEIVED' AND now() > due)
             OR (status = 'RECEIVED' AND now() > due);
    warned := breached OR coalesce(fr_at, now()) > created + make_interval(secs => (sla_min * 60 * 0.8)::int);

    -- 설문: 해결 건의 75% 응답. 별점은 상담원 평균 ± , 불만 고객은 낮게
    rating := NULL; submitted := NULL;
    IF status = 'RESOLVED' AND random() < 0.75 THEN
      submitted := resolved + make_interval(mins => 20 + floor(random() * 60 * 30)::int);
      IF submitted > now() OR submitted > resolved + interval '72 hours' THEN
        submitted := NULL;
      ELSE
        rating := greatest(1, least(5, round(a_rating[agent_idx] + (random() - 0.5) * 1.8
                  - CASE WHEN sentiment = 'NEGATIVE' THEN 0.8 ELSE 0 END)))::int;
      END IF;
    END IF;
    IF status = 'RESOLVED' THEN
      IF submitted IS NOT NULL THEN
        status := 'CLOSED'; closed := submitted;              -- 설문 제출 → CLOSED
      ELSIF resolved + interval '72 hours' < now() THEN
        status := 'CLOSED'; closed := resolved + interval '72 hours';  -- 미응답 72시간 자동 종료
      END IF;
    END IF;

    ticket_no := 'HN-' || to_char(created AT TIME ZONE 'Asia/Seoul', 'YYYYMMDD') || '-' || lpad(nextval('ticket_no_seq')::text, 6, '0');
    INSERT INTO ticket (ticket_no, customer_id, guest_name, guest_email, guest_password_hash, title, content, channel, category,
                        priority, sentiment, status, agent_id, first_response_due_at, first_responded_at, sla_warned, sla_breached,
                        assigned_at, resolved_at, closed_at, created_at, updated_at)
    VALUES (ticket_no, customer_id, guest_name, guest_email,
            CASE WHEN guest_email IS NOT NULL THEN current_setting('demo.pw_hash') END,
            title, body, 'WEB', cat, prio, sentiment, status, agent_id, due, fr_at, warned, breached,
            assigned, resolved, closed, created, coalesce(closed, resolved, fr_at, assigned, created))
    RETURNING ticket.ticket_id INTO tid;

    -- AI 분류 결과 (접수 후 수 초)
    INSERT INTO ticket_ai_result (ticket_id, category, urgency, sentiment, summary, confidence, status, model, latency_ms, created_at, updated_at)
    VALUES (tid, cat, base_prio, sentiment, left(customer_name || ' 고객: ' || title, 500), round((0.72 + random() * 0.26)::numeric, 2),
            'SUCCESS', 'gemini-3.5-flash-lite', 700 + floor(random() * 1900)::int,
            created + interval '4 seconds', created + interval '4 seconds');

    -- 이력
    INSERT INTO ticket_history (ticket_id, action, from_value, to_value, actor_id, actor_type, created_at)
    VALUES (tid, 'CREATE', NULL, 'RECEIVED', customer_id, CASE WHEN customer_id IS NULL THEN 'GUEST' ELSE 'MEMBER' END, created);
    IF prio <> base_prio THEN
      INSERT INTO ticket_history (ticket_id, action, from_value, to_value, actor_type, created_at)
      VALUES (tid, 'PRIORITY_CHANGE', base_prio, prio, 'SYSTEM', created + interval '5 seconds');
    END IF;
    IF assigned IS NOT NULL THEN
      INSERT INTO ticket_history (ticket_id, action, to_value, actor_type, created_at)
      VALUES (tid, 'ASSIGN', agent_id::text, 'SYSTEM', assigned);
    END IF;

    -- 답변: (가끔) 내부 메모 → 첫 공개 답변 → (가끔) 고객 추가 문의 + 재답변
    IF fr_at IS NOT NULL THEN
      IF random() < 0.2 THEN
        INSERT INTO ticket_reply (ticket_id, writer_id, writer_type, content, is_internal, created_at)
        VALUES (tid, agent_id, 'AGENT', memos[1 + floor(random() * array_length(memos, 1))::int], true, fr_at - interval '2 minutes');
      END IF;
      INSERT INTO ticket_reply (ticket_id, writer_id, writer_type, content, created_at)
      VALUES (tid, agent_id, 'AGENT', '안녕하세요, ' || customer_name || '님. HelpNest 고객센터입니다.' || E'\n\n' || (answers ->> cat), fr_at);
      INSERT INTO ticket_history (ticket_id, action, from_value, to_value, actor_id, actor_type, created_at)
      VALUES (tid, 'STATUS_CHANGE', 'ASSIGNED', 'IN_PROGRESS', agent_id, 'MEMBER', fr_at);
      IF resolved IS NOT NULL AND random() < 0.25 THEN
        INSERT INTO ticket_reply (ticket_id, writer_id, writer_type, content, created_at)
        VALUES (tid, customer_id, CASE WHEN customer_id IS NULL THEN 'GUEST' ELSE 'CUSTOMER' END,
                follow_ups[1 + floor(random() * array_length(follow_ups, 1))::int], fr_at + (resolved - fr_at) / 3);
        INSERT INTO ticket_reply (ticket_id, writer_id, writer_type, content, created_at)
        VALUES (tid, agent_id, 'AGENT', '추가 확인 결과 정상 처리된 것을 확인했습니다. 더 궁금하신 점이 있으면 언제든 문의해 주세요.',
                fr_at + (resolved - fr_at) * 2 / 3);
      END IF;
    END IF;
    IF resolved IS NOT NULL THEN
      INSERT INTO ticket_history (ticket_id, action, from_value, to_value, actor_id, actor_type, created_at)
      VALUES (tid, 'STATUS_CHANGE', 'IN_PROGRESS', 'RESOLVED', agent_id, 'MEMBER', resolved);
      INSERT INTO survey (ticket_id, token, rating, comment, sent_at, expires_at, submitted_at)
      VALUES (tid, md5(random()::text) || md5(random()::text), rating,
              CASE WHEN rating IS NULL THEN NULL
                   WHEN rating >= 5 THEN comments_good[1 + floor(random() * array_length(comments_good, 1))::int]
                   WHEN rating >= 3 THEN comments_mid[1 + floor(random() * array_length(comments_mid, 1))::int]
                   ELSE comments_bad[1 + floor(random() * array_length(comments_bad, 1))::int] END,
              resolved, CASE WHEN closed IS NOT NULL AND rating IS NULL THEN closed ELSE resolved + interval '72 hours' END,
              submitted);
    END IF;
    IF closed IS NOT NULL THEN
      INSERT INTO ticket_history (ticket_id, action, from_value, to_value, actor_type, created_at)
      VALUES (tid, 'STATUS_CHANGE', 'RESOLVED', 'CLOSED', 'SYSTEM', closed);
    END IF;

    -- 열린 담당 티켓은 상담원 알림에 "새 배정"으로 (최근 2일)
    IF status IN ('ASSIGNED','IN_PROGRESS') AND created > now() - interval '2 days' THEN
      INSERT INTO notification (receiver_id, type, ticket_id, message, is_read, created_at)
      VALUES (agent_id, 'ASSIGNED', tid, '새 문의가 배정되었습니다. (' || ticket_no || ') ' || title, status = 'IN_PROGRESS', assigned);
    END IF;
  END LOOP;

  UPDATE member m SET last_assigned_at = x.last_at
  FROM (SELECT agent_id, max(assigned_at) AS last_at FROM ticket WHERE agent_id = ANY (a_ids) GROUP BY agent_id) x
  WHERE m.member_id = x.agent_id;
END $$;

-- ─── 5. 결과 요약 ────────────────────────────────────────────────────────
SELECT m.name AS 상담원, count(t.*) AS 티켓, count(*) FILTER (WHERE t.sla_breached) AS sla위반,
       round(avg(s.rating), 2) AS 평균별점, count(*) FILTER (WHERE t.status IN ('ASSIGNED','IN_PROGRESS')) AS 진행중
FROM member m LEFT JOIN ticket t ON t.agent_id = m.member_id LEFT JOIN survey s ON s.ticket_id = t.ticket_id
WHERE m.email LIKE 'agent.%@demo.helpnest.kro.kr'
GROUP BY m.name ORDER BY 티켓 DESC;
SELECT status, count(*) FROM ticket WHERE customer_id IN (SELECT member_id FROM member WHERE email LIKE '%@demo.helpnest.kro.kr')
   OR guest_email LIKE '%@demo.helpnest.kro.kr' GROUP BY status ORDER BY 2 DESC;

COMMIT;
