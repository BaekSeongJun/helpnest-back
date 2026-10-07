#!/usr/bin/env bash
# @owner SSJ — jar 빌드 → EC2 업로드 → 교체·재시작 → health 확인 (deploy/README.md)
# 사용: deploy/deploy.sh <ec2-user@host> <ssh-key.pem>
set -euo pipefail

HOST=${1:?사용: deploy.sh <ec2-user@host> <ssh-key.pem>}
KEY=${2:?ssh 키 경로가 필요합니다}
cd "$(dirname "$0")/.."

# 커밋된 HEAD 만 빌드 — 작업 폴더에서 빌드하면 gitignore 된 application-local-secret.yml(LLM 키)이 jar 에 들어간다
BUILD=$(mktemp -d)
trap 'rm -rf "$BUILD"' EXIT
git archive HEAD | tar -x -C "$BUILD"
(cd "$BUILD" && ./mvnw -B -q package -DskipTests)
JAR=$(ls "$BUILD"/target/helpnest-back-*.jar | grep -v plain | head -1)
if unzip -l "$JAR" | grep -q secret; then echo "jar 에 secret 파일이 있습니다 — 중단" >&2; exit 1; fi

scp -i "$KEY" "$JAR" "$HOST:/tmp/helpnest-back.jar"
ssh -i "$KEY" "$HOST" 'set -e
  sudo cp -p /opt/helpnest/helpnest-back.jar /opt/helpnest/helpnest-back.jar.bak 2>/dev/null || true
  sudo install -o helpnest -g helpnest -m 644 /tmp/helpnest-back.jar /opt/helpnest/helpnest-back.jar
  rm /tmp/helpnest-back.jar
  sudo systemctl restart helpnest-back
  for i in $(seq 1 30); do
    curl -fs localhost:8080/actuator/health && echo && exit 0
    sleep 3
  done
  echo "health 실패 — sudo journalctl -u helpnest-back -n 100" >&2
  exit 1'
