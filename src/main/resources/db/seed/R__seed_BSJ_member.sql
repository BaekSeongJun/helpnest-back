-- @owner BSJ
-- 역할별 테스트 계정 (docs/03 §5). local 프로필에서만 적용 (application-local.yml flyway.locations)
-- 비밀번호 공통: Test1234!  (BCrypt)
--
-- 다른 시드에서 회원을 참조할 때는 member_id 를 숫자로 쓰지 말고 이메일로 조회한다
-- (테스트 롤백에도 시퀀스는 증가하므로 id 가 환경마다 다르다):
--   (SELECT member_id FROM member WHERE email = 'agent1@helpnest.local')
--
-- Repeatable(R__) 이라 이 파일이 바뀔 때마다 다시 실행된다 → ON CONFLICT 로 중복 방지
INSERT INTO member (email, password, name, phone, role, status, available) VALUES
  ('admin@helpnest.local',     '$2a$10$X/m5TZ7VP1lZis8l/RPCMu7sTQvPjd69lptE9bb9ePqIJCIJP5wga', '관리자',   '010-1000-0001', 'ADMIN',    'ACTIVE', FALSE),
  ('lead@helpnest.local',      '$2a$10$X/m5TZ7VP1lZis8l/RPCMu7sTQvPjd69lptE9bb9ePqIJCIJP5wga', '김팀장',   '010-1000-0002', 'LEAD',     'ACTIVE', FALSE),
  ('agent1@helpnest.local',    '$2a$10$X/m5TZ7VP1lZis8l/RPCMu7sTQvPjd69lptE9bb9ePqIJCIJP5wga', '이상담',   '010-2000-0001', 'AGENT',    'ACTIVE', TRUE),
  ('agent2@helpnest.local',    '$2a$10$X/m5TZ7VP1lZis8l/RPCMu7sTQvPjd69lptE9bb9ePqIJCIJP5wga', '박상담',   '010-2000-0002', 'AGENT',    'ACTIVE', TRUE),
  ('agent3@helpnest.local',    '$2a$10$X/m5TZ7VP1lZis8l/RPCMu7sTQvPjd69lptE9bb9ePqIJCIJP5wga', '최상담',   '010-2000-0003', 'AGENT',    'ACTIVE', TRUE),
  ('customer1@helpnest.local', '$2a$10$X/m5TZ7VP1lZis8l/RPCMu7sTQvPjd69lptE9bb9ePqIJCIJP5wga', '정고객',   '010-3000-0001', 'CUSTOMER', 'ACTIVE', FALSE),
  ('customer2@helpnest.local', '$2a$10$X/m5TZ7VP1lZis8l/RPCMu7sTQvPjd69lptE9bb9ePqIJCIJP5wga', '강고객',   '010-3000-0002', 'CUSTOMER', 'ACTIVE', FALSE),
  ('customer3@helpnest.local', '$2a$10$X/m5TZ7VP1lZis8l/RPCMu7sTQvPjd69lptE9bb9ePqIJCIJP5wga', '윤고객',   '010-3000-0003', 'CUSTOMER', 'ACTIVE', FALSE)
ON CONFLICT (email) DO NOTHING;
