# 백엔드 배포 (EC2) — @owner SSJ

구성: CloudFront(HTTPS/WSS) → EC2 `:8080`(systemd, `prod` 프로필) → RDS PostgreSQL. 자원 구성은 helpnest-docs `docs/02_architecture.md` §8.

- `prod` 는 Flyway `db/migration` 만 적용 (시드 없음 → 운영 초기 계정은 별도 생성)
- AWS 자격 증명 키는 두지 않는다 — EC2 IAM Role (SDK 기본 체인)
- 필수 환경변수가 비면 기동 즉시 실패 (`helpnest.env.example` 상단 목록)

## 1. EC2 최초 설정 (1회)

Amazon Linux 2023, Corretto 21 기준.

```bash
sudo dnf install -y java-21-amazon-corretto-headless
sudo useradd --system --no-create-home --shell /sbin/nologin helpnest
sudo mkdir -p /opt/helpnest /etc/helpnest
sudo chown helpnest:helpnest /opt/helpnest

# 환경변수: helpnest.env.example 을 복사해 값 채움 (root 만 읽기)
sudo vi /etc/helpnest/helpnest.env
sudo chmod 600 /etc/helpnest/helpnest.env

# 로컬에서 helpnest-back.service 업로드 후
sudo cp helpnest-back.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable helpnest-back
```

## 2. 배포 · 재배포

로컬(Git Bash)에서 repo 루트 기준:

```bash
deploy/deploy.sh ec2-user@<ec2-public-dns> ~/.ssh/helpnest.pem
```

**커밋된 HEAD** 를 임시 폴더에 꺼내 빌드(테스트 생략, 테스트는 CI) → jar 업로드 → 이전 jar 를 `helpnest-back.jar.bak` 으로 보관 → 재시작 → `localhost:8080/actuator/health` 200 대기(최대 90초).
작업 폴더에서 `./mvnw package` 한 jar 는 올리지 않는다 — gitignore 된 `application-local-secret.yml`(LLM 키)이 포함된다.
환경변수만 바꿨다면 EC2 에서 `sudo systemctl restart helpnest-back`.

## 3. 로그

```bash
sudo journalctl -u helpnest-back -f          # 실시간
sudo journalctl -u helpnest-back -n 200      # 최근 200줄
```

## 4. 롤백

```bash
sudo cp -p /opt/helpnest/helpnest-back.jar.bak /opt/helpnest/helpnest-back.jar
sudo systemctl restart helpnest-back
```

Flyway 마이그레이션은 되돌리지 않는다. 스키마 변경이 포함된 배포를 롤백해야 하면 새 마이그레이션으로 고친다.
