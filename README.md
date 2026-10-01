<!-- @owner BSJ -->
# HelpNest Backend

고객 문의 접수부터 AI 분류·자동 배정·SLA 관리·해결까지 처리하는 CS 헬프데스크 HelpNest 의 백엔드(Spring Boot 4, Java 21, PostgreSQL)입니다.
요구사항·설계 문서는 [helpnest-docs](https://github.com/BaekSeongJun/helpnest-docs) 에 있습니다.

## 로컬 실행

```bash
docker compose up -d                                    # PostgreSQL 16 (helpnest / helpnest)
SPRING_PROFILES_ACTIVE=local ./mvnw spring-boot:run     # http://localhost:8080
```

- `local` 프로필은 DB 접속값·개발용 JWT 키 기본값을 갖고, 시드 계정(`db/seed`)을 넣습니다 — `admin@helpnest.local`, `lead@…`, `agent1~3@…`, `customer1~3@…` / 비밀번호 `Test1234!`
- 그 밖의 환경변수는 [`.env.example`](.env.example) 참고 (LLM 은 기본 `mock`)
- 로컬 http 에서 Refresh 쿠키를 쓰려면 `REFRESH_COOKIE_SECURE=false`

## 테스트

```bash
SPRING_PROFILES_ACTIVE=local ./mvnw verify
# PowerShell: $env:SPRING_PROFILES_ACTIVE='local'; ./mvnw verify
```

프로필이 없으면 `DB_URL` 등이 비어 기동에 실패합니다. CI 도 같은 값으로 실행합니다 ([docs/10 §4](https://github.com/BaekSeongJun/helpnest-docs/blob/dev/docs/10_coding-conventions.md)).

## 문서

- API 명세: [docs/04](https://github.com/BaekSeongJun/helpnest-docs/blob/dev/docs/04_api-spec.md) · DB: [docs/03](https://github.com/BaekSeongJun/helpnest-docs/blob/dev/docs/03_database.md) · 아키텍처·도메인 연동: [docs/02](https://github.com/BaekSeongJun/helpnest-docs/blob/dev/docs/02_architecture.md)
- 협업 규칙(브랜치·커밋·파일 소유권): [docs/01](https://github.com/BaekSeongJun/helpnest-docs/blob/dev/docs/01_collaboration-rules.md)
- AI 분류 정확도·측정 방법: helpnest-docs [`docs/05_ai-automation.md` §6](https://github.com/BaekSeongJun/helpnest-docs/blob/dev/docs/05_ai-automation.md#6-품질-확인) (유형 30/30, 불만 재현율 10/10, gemini-3.5-flash-lite)
