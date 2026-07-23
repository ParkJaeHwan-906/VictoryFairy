#!/usr/bin/env bash
# db-tunnel.sh — 데이터 EC2(MySQL/Redis)로의 SSM 포트포워딩 터널을 백그라운드로 관리한다.
#
# 사용법:
#   ./scripts/db-tunnel.sh start    # mysql(3306)·redis(6379) 터널을 백그라운드로 시작
#   ./scripts/db-tunnel.sh stop     # 터널 종료
#   ./scripts/db-tunnel.sh status   # 터널 상태 확인
#
# 이후 HeidiSQL 등은 127.0.0.1:3306 / 127.0.0.1:6379 로 접속하면 된다.
# 창을 계속 켜둘 필요가 없다(nohup 백그라운드). 로그: /tmp/vf-tunnel-*.log
#
# ⚠ SSM 세션은 일정 시간 미사용 시(기본 20분) AWS가 자동 종료할 수 있다.
#   끊겼으면 다시 `start` 하면 된다(이미 떠 있으면 재시작하지 않음).

set -euo pipefail

REGION="ap-northeast-2"
PID_DIR="/tmp/vf-tunnel"
mkdir -p "$PID_DIR"

# 인스턴스 ID 조회 (하드코딩 금지 — 인스턴스 교체 대응). 우선순위:
#   1) 환경변수 VF_DB_INSTANCE_ID  (수동 지정)
#   2) terraform output            (state 를 가진 머신 — apply 실행자)
#   3) EC2 Name 태그 조회          (팀원 — state 없이 AWS API 로 조회)
resolve_instance_id() {
  if [[ -n "${VF_DB_INSTANCE_ID:-}" ]]; then
    echo "$VF_DB_INSTANCE_ID"; return
  fi
  local from_tf
  from_tf=$(terraform -chdir="$(dirname "$0")/../environments/dev" output -raw mysql_instance_id 2>/dev/null || true)
  if [[ "$from_tf" == i-* ]]; then
    echo "$from_tf"; return
  fi
  aws ec2 describe-instances --region "$REGION" \
    --filters "Name=tag:Name,Values=victoryfairy-mysql-dev" \
              "Name=instance-state-name,Values=running" \
    --query "Reservations[0].Instances[0].InstanceId" --output text
}

INSTANCE_ID=$(resolve_instance_id)
if [[ "$INSTANCE_ID" != i-* ]]; then
  echo "⚠ 데이터 EC2 인스턴스를 찾지 못했습니다. AWS 자격 증명을 확인하거나"
  echo "  VF_DB_INSTANCE_ID=i-xxxx ./scripts/db-tunnel.sh start 로 직접 지정하세요."
  exit 1
fi

# 포트 매핑: "원격포트:로컬포트:이름"
# ssh(22→2222): Termius/MobaXterm 등 SSH 클라이언트용 — 사전에 공개키가
#   ec2-user 의 authorized_keys 에 등록되어 있어야 한다(README 참고).
TUNNELS=("3306:3306:mysql" "6379:6379:redis" "22:2222:ssh")

is_running() { # $1=이름 → 살아있으면 0
  local pid_file="$PID_DIR/$1.pid"
  [[ -f "$pid_file" ]] && kill -0 "$(cat "$pid_file")" 2>/dev/null
}

case "${1:-}" in
  start)
    for t in "${TUNNELS[@]}"; do
      IFS=: read -r remote local name <<< "$t"
      if is_running "$name"; then
        echo "[$name] 이미 실행 중 (127.0.0.1:$local)"
        continue
      fi
      nohup aws ssm start-session --region "$REGION" \
        --target "$INSTANCE_ID" \
        --document-name AWS-StartPortForwardingSession \
        --parameters "{\"portNumber\":[\"$remote\"],\"localPortNumber\":[\"$local\"]}" \
        > "/tmp/vf-tunnel-$name.log" 2>&1 &
      echo $! > "$PID_DIR/$name.pid"
      # 리스닝 대기(최대 10초)
      for _ in $(seq 1 10); do
        lsof -iTCP:"$local" -sTCP:LISTEN >/dev/null 2>&1 && break
        sleep 1
      done
      if lsof -iTCP:"$local" -sTCP:LISTEN >/dev/null 2>&1; then
        echo "[$name] 터널 열림 → 127.0.0.1:$local"
      else
        echo "[$name] ⚠ 터널 실패 — 로그 확인: /tmp/vf-tunnel-$name.log"
      fi
    done
    ;;
  stop)
    for t in "${TUNNELS[@]}"; do
      IFS=: read -r _ _ name <<< "$t"
      if is_running "$name"; then
        kill "$(cat "$PID_DIR/$name.pid")" 2>/dev/null || true
        rm -f "$PID_DIR/$name.pid"
        echo "[$name] 종료"
      else
        rm -f "$PID_DIR/$name.pid"
        echo "[$name] 실행 중 아님"
      fi
    done
    ;;
  status)
    for t in "${TUNNELS[@]}"; do
      IFS=: read -r _ local name <<< "$t"
      if is_running "$name" && lsof -iTCP:"$local" -sTCP:LISTEN >/dev/null 2>&1; then
        echo "[$name] ✅ 열림 (127.0.0.1:$local)"
      else
        echo "[$name] ❌ 닫힘"
      fi
    done
    ;;
  *)
    echo "사용법: $0 {start|stop|status}"
    exit 1
    ;;
esac
