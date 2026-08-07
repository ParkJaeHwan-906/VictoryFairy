#!/usr/bin/env bash
# db-tunnel.sh — 개발 접속 통로 일괄 관리: 데이터 EC2(MySQL/Redis)·EKS 노드로의
# SSM 포트포워딩 + Kubernetes Dashboard port-forward 를 백그라운드로 띄운다.
#
# 사용법:
#   ./scripts/db-tunnel.sh start    # mysql(3306)·redis(6379)·ssh(2222)·node-ssh(2223)·dashboard(8443) 시작
#   ./scripts/db-tunnel.sh stop     # 전부 종료
#   ./scripts/db-tunnel.sh status   # 상태 확인
#   ./scripts/db-tunnel.sh token    # 대시보드 로그인 토큰을 클립보드에 복사
#
# 이후 HeidiSQL 등은 127.0.0.1:3306 / 127.0.0.1:6379 로 접속하면 된다.
# 대시보드는 https://localhost:8443 (start 시 토큰이 클립보드에 복사됨).
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

# EKS app 노드 인스턴스 ID 조회. 노드는 오토스케일로 교체되므로 항상 태그로 찾는다.
#   1) 환경변수 VF_NODE_INSTANCE_ID (수동 지정 — 특정 노드를 골라야 할 때)
#   2) EKS 노드그룹 태그 조회        (running 중 첫 번째 노드)
# 전제: 노드 롤에 AmazonSSMManagedInstanceCore 정책이 붙어 있어야 한다(eks 모듈).
resolve_node_instance_id() {
  if [[ -n "${VF_NODE_INSTANCE_ID:-}" ]]; then
    echo "$VF_NODE_INSTANCE_ID"; return
  fi
  aws ec2 describe-instances --region "$REGION" \
    --filters "Name=tag:eks:cluster-name,Values=victoryfairy-dev" \
              "Name=tag:eks:nodegroup-name,Values=victoryfairy-dev-app" \
              "Name=instance-state-name,Values=running" \
    --query "Reservations[0].Instances[0].InstanceId" --output text
}

INSTANCE_ID=$(resolve_instance_id)
if [[ "$INSTANCE_ID" != i-* ]]; then
  echo "⚠ 데이터 EC2 인스턴스를 찾지 못했습니다. AWS 자격 증명을 확인하거나"
  echo "  VF_DB_INSTANCE_ID=i-xxxx ./scripts/db-tunnel.sh start 로 직접 지정하세요."
  exit 1
fi

# 포트 매핑: "원격포트:로컬포트:이름:대상"
#   대상 db   = MySQL EC2 (SSM),  node = EKS app 노드 (SSM),  k8s = kubectl port-forward
# ssh(22→2222): Termius/MobaXterm 등 SSH 클라이언트용 — 사전에 공개키가
#   ec2-user 의 authorized_keys 에 등록되어 있어야 한다(README 참고).
# node-ssh(22→2223): EKS app 노드 SSH — 키페어 VictoryFairy(pem)로 접속.
#   SG 는 22번을 외부에 열지 않으며, SSM 터널은 SG 를 경유하지 않으므로 무관.
# dashboard(443→8443): Kubernetes Dashboard(k8s/90-*.yaml) — https://localhost:8443
TUNNELS=("3306:3306:mysql:db" "6379:6379:redis:db" "22:2222:ssh:db" "22:2223:node-ssh:node" "443:8443:dashboard:k8s")

# 대시보드 로그인 토큰(장기 토큰, 값 불변)을 클립보드에 복사. 클립보드 도구가
# 없는 환경에서는 화면에 출력한다. (91-dashboard-admin-user.yaml 의 admin-user-token)
print_token() {
  local jwt
  jwt=$(kubectl -n kubernetes-dashboard get secret admin-user-token \
    -o jsonpath='{.data.token}' 2>/dev/null | base64 -d 2>/dev/null)
  if [[ -z "$jwt" ]]; then
    echo "⚠ 대시보드 토큰 조회 실패 — kubectl 컨텍스트와 k8s/91-dashboard-admin-user.yaml 적용 여부를 확인하세요."
    return 1
  fi
  if command -v clip.exe >/dev/null 2>&1; then          # Windows (Git Bash)
    printf '%s' "$jwt" | clip.exe
    echo "🔑 대시보드 토큰을 클립보드에 복사했습니다 → https://localhost:8443 (Token 로그인, Ctrl+V)"
  elif command -v pbcopy >/dev/null 2>&1; then          # macOS
    printf '%s' "$jwt" | pbcopy
    echo "🔑 대시보드 토큰을 클립보드에 복사했습니다 → https://localhost:8443 (Token 로그인, Cmd+V)"
  else
    echo "🔑 대시보드 토큰 (아래 전체를 복사 → https://localhost:8443):"
    echo "$jwt"
  fi
}

is_running() { # $1=이름 → 살아있으면 0
  local pid_file="$PID_DIR/$1.pid"
  [[ -f "$pid_file" ]] && kill -0 "$(cat "$pid_file")" 2>/dev/null
}

port_listening() { # $1=로컬포트 → 리스닝 중이면 0. lsof 없는 환경(Windows Git Bash)은 /dev/tcp 폴백
  if command -v lsof >/dev/null 2>&1; then
    lsof -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
  else
    (exec 3<>"/dev/tcp/127.0.0.1/$1" && exec 3>&-) 2>/dev/null
  fi
}

case "${1:-}" in
  start)
    NODE_INSTANCE_ID=""  # node 대상 터널이 있을 때만 지연 조회
    for t in "${TUNNELS[@]}"; do
      IFS=: read -r remote local name target <<< "$t"
      if is_running "$name"; then
        echo "[$name] 이미 실행 중 (127.0.0.1:$local)"
        continue
      fi
      # 이 스크립트 밖에서 이미 포트를 점유 중이면(수동 port-forward 등) 이중 기동하지 않는다.
      if port_listening "$local"; then
        echo "[$name] 이미 열려 있음(외부 프로세스) → 127.0.0.1:$local"
        continue
      fi
      if [[ "$target" == "k8s" ]]; then
        # kubectl port-forward (EKS API 경유 — SSM 아님)
        if ! command -v kubectl >/dev/null 2>&1; then
          echo "[$name] ⚠ kubectl 없음 — 건너뜀"
          continue
        fi
        nohup kubectl -n kubernetes-dashboard port-forward \
          svc/kubernetes-dashboard "$local:$remote" \
          > "/tmp/vf-tunnel-$name.log" 2>&1 &
      else
        # SSM 포트포워딩 — 터널별 대상 인스턴스 결정
        target_id="$INSTANCE_ID"
        if [[ "$target" == "node" ]]; then
          if [[ -z "$NODE_INSTANCE_ID" ]]; then
            NODE_INSTANCE_ID=$(resolve_node_instance_id)
          fi
          if [[ "$NODE_INSTANCE_ID" != i-* ]]; then
            echo "[$name] ⚠ EKS app 노드를 찾지 못해 건너뜀 (VF_NODE_INSTANCE_ID=i-xxxx 로 직접 지정 가능)"
            continue
          fi
          target_id="$NODE_INSTANCE_ID"
        fi
        nohup aws ssm start-session --region "$REGION" \
          --target "$target_id" \
          --document-name AWS-StartPortForwardingSession \
          --parameters "{\"portNumber\":[\"$remote\"],\"localPortNumber\":[\"$local\"]}" \
          > "/tmp/vf-tunnel-$name.log" 2>&1 &
      fi
      echo $! > "$PID_DIR/$name.pid"
      # 리스닝 대기(최대 10초)
      for _ in $(seq 1 10); do
        port_listening "$local" && break
        sleep 1
      done
      if port_listening "$local"; then
        echo "[$name] 터널 열림 → 127.0.0.1:$local"
      else
        echo "[$name] ⚠ 터널 실패 — 로그 확인: /tmp/vf-tunnel-$name.log"
      fi
    done
    # 대시보드 통로가 열려 있으면 로그인 토큰까지 클립보드에 준비해 준다.
    if port_listening 8443; then
      print_token || true
    fi
    ;;
  stop)
    for t in "${TUNNELS[@]}"; do
      IFS=: read -r _ _ name _ <<< "$t"
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
      IFS=: read -r _ local name _ <<< "$t"
      if port_listening "$local"; then
        if is_running "$name"; then
          echo "[$name] ✅ 열림 (127.0.0.1:$local)"
        else
          echo "[$name] ✅ 열림 — 외부 프로세스 (127.0.0.1:$local)"
        fi
      else
        echo "[$name] ❌ 닫힘"
      fi
    done
    ;;
  token)
    print_token
    ;;
  *)
    echo "사용법: $0 {start|stop|status|token}"
    exit 1
    ;;
esac
