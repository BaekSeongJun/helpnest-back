-- @owner SSJ
-- 재시도 시 다시 렌더링하지 않도록 발송한 제목·본문을 저장 (docs/05 §5)
-- 주의: body 에 설문·재설정 토큰 URL 이 포함된다 → 조회 권한을 운영자로 제한
ALTER TABLE mail_log
    ADD COLUMN subject VARCHAR(200),
    ADD COLUMN body    TEXT;
