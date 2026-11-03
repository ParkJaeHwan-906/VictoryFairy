#!/usr/bin/env bash
# redis-enable-aof.sh — 운영 데이터 EC2(MySQL+Redis 동거)의 서비스 Redis 를
#   (1) AOF(appendonly yes, appendfsync everysec)로 바꾸고 (2) maxmemory 를 목표값(기본 512mb)으로 올린다.
# chat 모듈의 당일 히스토리(Redis Stream)가 Redis 재시작에 사라지지 않게 하고, 그 몫(~100MB)을 담으려는
# 선행 조건이다(VictoryFairy_BE docs/requirements/chat/game-chat.md "인프라 선행 조건" 3).
# maxmemory-policy(allkeys-lru)는 바꾸지 않는다(사용자 결정 2026-10-09).
#
# 왜 Terraform(user_data)이 아니라 이 스크립트인가:
#   aws_instance.user_data 를 바꾸면 cloud-init 은 재실행되지 않고 인스턴스 stop/start 만 일어난다
#   (운영 MySQL 다운). 그래서 modules/mysql-ec2 는 user_data 를 ignore_changes 하고, 실행 중 호스트는
#   SSM 으로 패치한다. 템플릿(user_data.sh.tftpl §5)은 이 스크립트의 결과와 같은 상태를 '다음 생성 시의
#   정답'으로 들고 있다.
#
# 왜 CONFIG SET + CONFIG REWRITE 로 끝나지 않는가:
#   현행 컨테이너는 설정 파일 없이 `redis-server --maxmemory ... --maxmemory-policy ...` 인자로만 뜬다.
#   이 상태에서 CONFIG REWRITE 는 "The server is running without a config file" 로 실패하고, CONFIG SET
#   만 하면 컨테이너가 재시작될 때 기동 인자로 돌아간다. 그래서 두 단계로 간다:
#     1단계(무중단) docker update --memory(필요 시 상한 상향) -> CONFIG SET maxmemory <목표>
#                  -> CONFIG SET appendonly yes -> AOF 재작성 완료 대기 -> CONFIG SET appendfsync everysec
#     2단계(수 초 중단) 같은 /data 볼륨을 물고 목표 maxmemory + AOF 인자 + --memory 상한으로 컨테이너 재생성
#   ⚠ 순서가 핵심이다. AOF 파일이 없는 상태에서 appendonly yes 로 기동하면 Redis 는 RDB 를 무시하고
#     '빈 데이터셋'으로 뜬다. 1단계가 현재 메모리를 AOF 로 먼저 써 두기 때문에 2단계가 안전하다.
#   ⚠ 상한 상향(docker update)이 maxmemory 상향보다 먼저다. 반대로 하면 512mb 데이터셋 + 재작성 fork 가
#     기존 512m cgroup 에 걸려 OOM 으로 redis 가 죽는다.
#   설정 파일로 뜨는 컨테이너라면(INFO config_file 이 비어 있지 않음) 2단계 대신 CONFIG REWRITE 로 끝낸다.
#
# 메모리 판정(check·apply 모두, apply 는 판정 실패 시 아무것도 바꾸지 않고 중단):
#   - 필요 상한 = 목표 maxmemory × 2(재작성 fork 의 COW 여유 1배) + 128MiB(오버헤드)
#     현재 --memory 가 이보다 작거나 무제한(0)이면 VF_REDIS_CONTAINER_MEMORY 로 올린다(그 값도 필요 상한 이상이어야 함).
#   - 호스트 available 은 (목표 - 현재 used_memory) + 목표(fork 여유) + 256MiB(OS 예비) 이상이어야 한다.
#   - mysql 컨테이너 상한 + 새 redis 상한 이 MemTotal - 256MiB 를 넘으면 중단(상한 합계를 RAM 안에 둔다 —
#     이 박스는 2026-08-17 mysqld RSS 1.49GB 로 라이브락이 난 이력이 있다).
#
# 환경변수:
#   VF_REDIS_MAXMEMORY        목표 maxmemory (Redis 표기, 기본 512mb)
#   VF_REDIS_CONTAINER_MEMORY 상한을 올려야 할 때 쓸 --memory (docker 표기, 기본 1152m)
#       근거: 512mb × 2 + 128m = 1152m — 필요 상한 공식의 최소값. t3.medium(4GB)에서 mysql 2g + 1152m ≈ 3.1GB
#       로 OS·docker 몫 ~0.7GB 를 남긴다. 더 크게 잡으면 상한 합계가 RAM 에 붙어 라이브락 여지를 다시 만든다.
#   VF_DB_INSTANCE_ID         대상 인스턴스 직접 지정(로컬 모드)
#
# 사용법 (로컬 Git Bash — SSM send-command 로 호스트에서 실행된다. 위 VF_REDIS_* 는 호스트로 전달된다):
#   ./scripts/redis-enable-aof.sh check    # 읽기 전용 점검 + 메모리 판정. 변경 없음
#   ./scripts/redis-enable-aof.sh apply    # 실제 적용 (2단계에서 Redis 가 수 초 끊긴다)
#   예: VF_REDIS_MAXMEMORY=512mb VF_REDIS_CONTAINER_MEMORY=1152m ./scripts/redis-enable-aof.sh check
# 호스트 셸(SSM start-session)에서 직접: sudo bash redis-enable-aof.sh host check|apply
#
# 되돌리기 (호스트에서. apply 출력의 '되돌리기 값' 줄에 이전 maxmemory·--memory 가 찍힌다 — 아래 <...> 에 넣는다):
#   * 2단계까지 끝난 뒤:
#       docker stop redis && docker rm redis && docker rename redis-pre-aof redis \
#         && docker update --restart=unless-stopped --memory <이전 --memory> --memory-swap <이전 --memory × 2> redis \
#         && docker start redis
#     이전 컨테이너는 옛 인자(이전 maxmemory, AOF 없음)로 뜨며 정지 시 저장된 RDB 를 읽는다.
#     데이터셋이 이전 maxmemory 보다 크면 allkeys-lru 로 축출된다.
#     (redis-pre-aof 는 정지 상태로 남겨 둔 것이다. 문제가 없으면 docker rm redis-pre-aof)
#   * 1단계에서 멈췄을 때(재생성 전 — 컨테이너는 그대로):
#       docker exec redis redis-cli CONFIG SET appendonly no
#       docker exec redis redis-cli CONFIG SET maxmemory <이전 maxmemory>
#       docker update --memory <이전 --memory> --memory-swap <이전 --memory × 2> redis

set -euo pipefail

REGION="ap-northeast-2"
CONTAINER="redis"
MYSQL_CONTAINER="mysql"
BACKUP_CONTAINER="redis-pre-aof"
TARGET_MAXMEMORY="${VF_REDIS_MAXMEMORY:-512mb}"
CONTAINER_MEMORY="${VF_REDIS_CONTAINER_MEMORY:-1152m}"
MIB=$((1024 * 1024))
OVERHEAD_BYTES=$((128 * MIB))
HOST_RESERVE_BYTES=$((256 * MIB))

# 형식 검증 — 로컬 모드에서는 이 값이 원격 명령 문자열에 들어가므로 여기서 막는다.
[[ "$TARGET_MAXMEMORY" =~ ^[0-9]+([kKmMgG][bB]?|[bB])?$ ]] || { echo "VF_REDIS_MAXMEMORY 형식 오류: $TARGET_MAXMEMORY (예: 512mb)" >&2; exit 2; }
[[ "$CONTAINER_MEMORY" =~ ^[0-9]+[bkmgBKMG]?$ ]] || { echo "VF_REDIS_CONTAINER_MEMORY 형식 오류: $CONTAINER_MEMORY (예: 1152m)" >&2; exit 2; }

# Redis 단위: k/m/g = 1000 계열, kb/mb/gb = 1024 계열. docker 단위: b/k/m/g 전부 1024 계열.
redis_bytes() {
  python3 -c '
import re, sys
m = re.fullmatch(r"(\d+)([a-z]*)", sys.argv[1].lower())
mult = {"": 1, "b": 1, "k": 1000, "kb": 1024, "m": 1000**2, "mb": 1024**2, "g": 1000**3, "gb": 1024**3}
print(int(m.group(1)) * mult[m.group(2)])
' "$1"
}
docker_bytes() {
  python3 -c '
import re, sys
m = re.fullmatch(r"(\d+)([a-z]?)", sys.argv[1].lower())
print(int(m.group(1)) * {"": 1, "b": 1, "k": 1024, "m": 1024**2, "g": 1024**3}[m.group(2)])
' "$1"
}
mib() { echo "$(( $1 / MIB ))MiB"; }

# -----------------------------------------------------------------------------
# 호스트에서 도는 부분
# -----------------------------------------------------------------------------
rcli() { docker exec "$CONTAINER" redis-cli "$@"; }
info_field() { rcli INFO "$1" | tr -d '\r' | awk -F: -v k="$2" '$1==k {print $2}'; }
cfg() { rcli CONFIG GET "$1" | tr -d '\r' | sed -n 2p; }

# Cmd 중 이 스크립트가 재구성할 줄 아는 인자를 뺀 나머지. 남는 게 있으면 재생성 시 유실 위험.
unknown_args() {
  python3 -c '
import json, sys
cmd = json.loads(sys.argv[1]) or []
known = {"--maxmemory", "--maxmemory-policy", "--appendonly", "--appendfsync"}
rest, i = [], 1 if cmd and cmd[0] == "redis-server" else 0
while i < len(cmd):
    if cmd[i] in known and i + 1 < len(cmd):
        i += 2
        continue
    rest.append(cmd[i])
    i += 1
print(" ".join(rest))
' "$1"
}

host_main() {
  local mode="$1"
  [[ "$(id -u)" == 0 ]] || { echo "root 로 실행해야 합니다(sudo)." >&2; exit 1; }
  docker inspect "$CONTAINER" >/dev/null 2>&1 || { echo "컨테이너 '$CONTAINER' 가 없습니다." >&2; exit 1; }
  [[ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER")" == true ]] || { echo "컨테이너 '$CONTAINER' 가 실행 중이 아닙니다." >&2; exit 1; }

  # -- 현행 상태 수집 ----------------------------------------------------------
  local version major image cur_cap cur_swap cmd_json mount_type mount_src config_file
  version="$(info_field server redis_version)"
  major="${version%%.*}"
  image="$(docker inspect -f '{{.Config.Image}}' "$CONTAINER")"
  cur_cap="$(docker inspect -f '{{.HostConfig.Memory}}' "$CONTAINER")"
  cur_swap="$(docker inspect -f '{{.HostConfig.MemorySwap}}' "$CONTAINER")"
  cmd_json="$(docker inspect -f '{{json .Config.Cmd}}' "$CONTAINER")"
  mount_type="$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Type}}{{end}}{{end}}' "$CONTAINER")"
  # redis 공식 이미지는 VOLUME /data 라 아무 -v 없이 떠도 익명 볼륨이 붙어 있다. 그 볼륨을 이름으로 재사용한다.
  if [[ "$mount_type" == volume ]]; then
    mount_src="$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Name}}{{end}}{{end}}' "$CONTAINER")"
  else
    mount_src="$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/data"}}{{.Source}}{{end}}{{end}}' "$CONTAINER")"
  fi
  config_file="$(info_field server config_file)"

  # 기동 인자가 아니라 '지금 살아 있는 값'을 읽는다 — SSM 으로 런타임 패치된 적이 있을 수 있다.
  local cur_mm policy appendonly appendfsync dbsize used_bytes
  cur_mm="$(cfg maxmemory)" # CONFIG GET 은 바이트로 돌려준다
  policy="$(cfg maxmemory-policy)"
  appendonly="$(cfg appendonly)"
  appendfsync="$(cfg appendfsync)"
  dbsize="$(rcli DBSIZE | tr -d '\r')"
  used_bytes="$(info_field memory used_memory)"

  local mem_total avail mysqld_rss_kb mysql_cap
  mem_total="$(awk '/^MemTotal:/ {print $2 * 1024}' /proc/meminfo)"
  avail="$(free -b | awk '/^Mem:/ {print $7}')"
  mysqld_rss_kb="$(ps -C mysqld -o rss= 2>/dev/null | awk '{s += $1} END {print s + 0}')"
  mysql_cap="$(docker inspect -f '{{.HostConfig.Memory}}' "$MYSQL_CONTAINER" 2>/dev/null || echo 0)"

  # -- 목표·판정 ----------------------------------------------------------------
  local target_mm required_cap env_cap new_cap need_host grow ok=1
  target_mm="$(redis_bytes "$TARGET_MAXMEMORY")"
  required_cap=$((target_mm * 2 + OVERHEAD_BYTES))
  env_cap="$(docker_bytes "$CONTAINER_MEMORY")"
  if (( cur_cap > 0 && cur_cap >= required_cap )); then
    new_cap="$cur_cap"
  else
    new_cap="$env_cap"
  fi
  grow=$((target_mm - used_bytes))
  (( grow < 0 )) && grow=0
  need_host=$((grow + target_mm + HOST_RESERVE_BYTES))

  echo "== 현행 Redis =="
  echo "  image            : $image"
  echo "  redis_version    : $version"
  echo "  container Cmd    : $cmd_json"
  echo "  config_file      : ${config_file:-(없음 - 인자 기동)}"
  echo "  /data mount      : ${mount_type:-(없음)} ${mount_src:-}"
  echo "  maxmemory        : $(mib "$cur_mm")  -> 목표 $TARGET_MAXMEMORY ($(mib "$target_mm"))"
  echo "  maxmemory-policy : $policy (유지)"
  echo "  appendonly       : $appendonly / appendfsync: $appendfsync"
  echo "  DBSIZE           : $dbsize / used_memory: $(mib "$used_bytes")"
  echo "  redis --memory   : $( (( cur_cap > 0 )) && mib "$cur_cap" || echo 무제한) (memory-swap=$cur_swap)"
  echo "== 호스트 =="
  echo "  MemTotal         : $(mib "$mem_total")"
  echo "  available        : $(mib "$avail")"
  echo "  mysqld RSS       : $((mysqld_rss_kb / 1024))MiB"
  echo "  mysql --memory   : $( (( mysql_cap > 0 )) && mib "$mysql_cap" || echo 무제한)"
  free -m | sed 's/^/    /'
  echo "== 메모리 판정 =="
  echo "  필요 redis 상한  : $(mib "$required_cap")  (목표 × 2 + 128MiB)"
  echo "  적용할 상한      : $(mib "$new_cap")  $( [[ "$new_cap" == "$cur_cap" ]] && echo '(현재 유지)' || echo "(VF_REDIS_CONTAINER_MEMORY=$CONTAINER_MEMORY 로 상향)")"
  if (( new_cap < required_cap )); then
    echo "  X 상한 부족: VF_REDIS_CONTAINER_MEMORY 를 최소 $((required_cap / MIB))m 로 지정할 것."
    ok=0
  fi
  echo "  필요 host avail  : $(mib "$need_host")  ((목표 - used) + 목표(fork) + 256MiB)"
  if (( avail < need_host )); then
    echo "  X 호스트 available 부족: $(mib "$avail") < $(mib "$need_host"). 목표 maxmemory 를 낮추거나 박스를 먼저 키울 것."
    ok=0
  fi
  if (( mysql_cap > 0 )); then
    echo "  상한 합계        : mysql $(mib "$mysql_cap") + redis $(mib "$new_cap") = $(mib $((mysql_cap + new_cap))) / 허용 $(mib $((mem_total - HOST_RESERVE_BYTES)))"
    if (( mysql_cap + new_cap > mem_total - HOST_RESERVE_BYTES )); then
      echo "  X 상한 합계가 RAM 을 넘는다(2026-08 라이브락 재발 조건). redis 상한을 $(((mem_total - HOST_RESERVE_BYTES - mysql_cap) / MIB))m 이하로."
      ok=0
    fi
  else
    echo "  ! mysql 컨테이너 상한이 없다(무제한) — 상한 합계 판정 불가. mysql --memory 를 먼저 확인할 것."
  fi

  (( major >= 6 )) || { echo "X Redis $version - chat 은 6.0+(SET KEEPTTL) 필요. 이미지 승급이 먼저다." >&2; exit 1; }
  [[ -n "$mount_type" ]] || { echo "X /data 에 마운트가 없다 - 재생성하면 데이터가 사라진다. 중단." >&2; exit 1; }

  local extra_args
  extra_args="$(unknown_args "$cmd_json")"
  if [[ -z "$config_file" && -n "$extra_args" ]]; then
    echo "X 기동 인자에 이 스크립트가 모르는 항목이 있다: $extra_args - 재구성 시 유실될 수 있어 중단. 수동 확인 필요." >&2
    exit 1
  fi

  if [[ "$appendonly" == yes && "$appendfsync" == everysec && "$cur_mm" == "$target_mm" && "$new_cap" == "$cur_cap" ]] \
    && [[ -n "$config_file" || ( "$cmd_json" == *"--appendonly"* && "$cmd_json" == *"\"$TARGET_MAXMEMORY\""* ) ]]; then
    echo "OK 이미 적용돼 있다(런타임 + 영속 설정 + 상한). 할 일 없음."
    return 0
  fi

  if (( ok == 0 )); then
    echo "X 메모리 판정 실패 - $( [[ "$mode" == apply ]] && echo '아무것도 바꾸지 않고 중단한다' || echo 'apply 는 중단될 것이다')." >&2
    exit 1
  fi

  if [[ "$mode" != apply ]]; then
    echo
    echo "check 모드 - 아무것도 바꾸지 않았다. 판정 통과. 적용하려면 'apply' 로 다시 실행."
    if [[ -z "$config_file" ]]; then
      echo "  apply 의 2단계(컨테이너 재생성)에서 Redis 가 수 초 끊긴다. 저트래픽 시간대에 실행할 것."
    fi
    return 0
  fi

  echo "되돌리기 값: 이전 maxmemory=${cur_mm}(bytes) 이전 --memory=${cur_cap}(bytes) memory-swap=${cur_swap}"

  # -- 1단계: 무중단 ---------------------------------------------------------------
  if [[ "$new_cap" != "$cur_cap" ]]; then
    echo "== 1단계-a: 컨테이너 상한 상향 (docker update, 재시작 없음) =="
    # memory-swap 은 docker run 기본(메모리 × 2)과 같은 관계로 맞춘다. 무제한(-1)이었으면 그대로 둔다.
    local swap_arg=$((new_cap * 2))
    [[ "$cur_swap" == -1 ]] && swap_arg=-1
    docker update --memory "${new_cap}b" --memory-swap "$( [[ "$swap_arg" == -1 ]] && echo -1 || echo "${swap_arg}b")" "$CONTAINER" >/dev/null
  fi

  echo "== 1단계-b: CONFIG SET maxmemory $TARGET_MAXMEMORY =="
  if [[ "$cur_mm" != "$target_mm" ]]; then
    rcli CONFIG SET maxmemory "$TARGET_MAXMEMORY"
  fi

  echo "== 1단계-c: CONFIG SET appendonly yes =="
  if [[ "$appendonly" != yes ]]; then
    rcli CONFIG SET appendonly yes
  fi
  local i
  for i in $(seq 1 120); do
    if [[ "$(info_field persistence aof_rewrite_in_progress)" == 0 && "$(info_field persistence aof_rewrite_scheduled)" == 0 ]]; then
      break
    fi
    if (( i == 120 )); then
      echo "X AOF 재작성이 600s 안에 끝나지 않았다. 2단계로 가지 않는다." >&2
      exit 1
    fi
    sleep 5
  done
  [[ "$(info_field persistence aof_last_bgrewrite_status)" == ok ]] || { echo "X AOF 재작성 실패(aof_last_bgrewrite_status). 중단." >&2; exit 1; }
  [[ "$(info_field persistence aof_enabled)" == 1 ]] || { echo "X aof_enabled != 1. 중단." >&2; exit 1; }
  rcli CONFIG SET appendfsync everysec
  echo "OK 런타임 적용: maxmemory=$(cfg maxmemory) appendonly=yes appendfsync=everysec"

  if [[ -n "$config_file" ]]; then
    rcli CONFIG REWRITE
    echo "OK 설정 파일($config_file)에 반영. 재생성 불필요(상한은 docker update 로 컨테이너 설정에 남는다)."
    return 0
  fi

  # -- 2단계: 재시작해도 유지되도록 기동 인자에 넣어 재생성 -------------------------
  echo "== 2단계: 컨테이너 재생성 (/data 볼륨 재사용) =="
  if docker inspect "$BACKUP_CONTAINER" >/dev/null 2>&1; then
    echo "X '$BACKUP_CONTAINER' 가 이미 있다(이전 실행 잔재). 확인 후 지우고 다시 실행. (1단계는 이미 런타임에 적용됨)" >&2
    exit 1
  fi

  # 정상 SHUTDOWN 이 AOF 를 fsync 하고(save 포인트가 있으면 RDB 도) 같은 볼륨에 남긴다.
  docker stop -t 30 "$CONTAINER"
  docker rename "$CONTAINER" "$BACKUP_CONTAINER"
  docker update --restart=no "$BACKUP_CONTAINER" >/dev/null

  docker run -d --name "$CONTAINER" --restart unless-stopped \
    -p 6379:6379 \
    --memory "${new_cap}b" \
    -v "$mount_src:/data" \
    "$image" \
    redis-server --maxmemory "$TARGET_MAXMEMORY" --maxmemory-policy "$policy" \
    --appendonly yes --appendfsync everysec

  for i in $(seq 1 60); do
    if [[ "$(rcli PING 2>/dev/null | tr -d '\r')" == PONG && "$(info_field persistence loading 2>/dev/null)" == 0 ]]; then
      break
    fi
    if (( i == 60 )); then
      echo "X 새 컨테이너가 60s 안에 응답하지 않는다. 파일 머리의 '되돌리기' 절차를 따를 것." >&2
      exit 1
    fi
    sleep 1
  done

  local after
  after="$(rcli DBSIZE | tr -d '\r')"
  echo "DBSIZE before=$dbsize after=$after (TTL 만료분만큼 줄 수 있다)"
  echo "maxmemory=$(cfg maxmemory) policy=$(cfg maxmemory-policy) appendonly=$(cfg appendonly) appendfsync=$(cfg appendfsync) aof_enabled=$(info_field persistence aof_enabled)"
  echo "--memory=$(docker inspect -f '{{.HostConfig.Memory}}' "$CONTAINER")"
  if (( after == 0 && dbsize > 0 )); then
    echo "X 데이터셋이 비었다. 즉시 파일 머리의 '되돌리기' 절차를 따를 것." >&2
    exit 1
  fi
  echo "OK 완료. 확인 후 'docker rm $BACKUP_CONTAINER' 로 이전 컨테이너를 지운다."
}

# -----------------------------------------------------------------------------
# 로컬에서 도는 부분 - 이 파일을 SSM send-command 로 호스트에 보내 실행한다
# -----------------------------------------------------------------------------
# 인스턴스 ID 조회 순서는 db-tunnel.sh 와 같다(환경변수 -> terraform output -> Name 태그).
resolve_instance_id() {
  if [[ -n "${VF_DB_INSTANCE_ID:-}" ]]; then
    echo "$VF_DB_INSTANCE_ID"
    return
  fi
  local from_tf
  from_tf=$(terraform -chdir="$(dirname "$0")/../environments/dev" output -raw mysql_instance_id 2>/dev/null || true)
  if [[ "$from_tf" == i-* ]]; then
    echo "$from_tf"
    return
  fi
  aws ec2 describe-instances --region "$REGION" \
    --filters "Name=tag:Name,Values=victoryfairy-mysql-dev" "Name=instance-state-name,Values=running" \
    --query "Reservations[0].Instances[0].InstanceId" --output text
}

remote_main() {
  local mode="$1" instance_id payload params command_id status
  instance_id="$(resolve_instance_id)"
  [[ "$instance_id" == i-* ]] || { echo "데이터 EC2 를 찾지 못했다. VF_DB_INSTANCE_ID=i-xxxx 로 지정할 것." >&2; exit 1; }
  echo "대상: $instance_id (mode=$mode, VF_REDIS_MAXMEMORY=$TARGET_MAXMEMORY, VF_REDIS_CONTAINER_MEMORY=$CONTAINER_MEMORY)"

  payload="$(base64 -w0 "$0")"
  # 두 값은 위에서 형식 검증을 통과한 것만 여기 들어간다(명령 문자열 주입 방지).
  params="$(printf '{"commands":["echo %s | base64 -d > /tmp/redis-enable-aof.sh","VF_REDIS_MAXMEMORY=%s VF_REDIS_CONTAINER_MEMORY=%s bash /tmp/redis-enable-aof.sh host %s"],"executionTimeout":["1200"]}' \
    "$payload" "$TARGET_MAXMEMORY" "$CONTAINER_MEMORY" "$mode")"

  # Git Bash 경로 자동변환이 인자를 망가뜨리지 않게 한다.
  command_id="$(MSYS_NO_PATHCONV=1 aws ssm send-command --region "$REGION" \
    --instance-ids "$instance_id" --document-name AWS-RunShellScript \
    --comment "redis-enable-aof $mode" --parameters "$params" \
    --query 'Command.CommandId' --output text)"
  echo "CommandId: $command_id"

  while :; do
    sleep 5
    status="$(aws ssm get-command-invocation --region "$REGION" --command-id "$command_id" \
      --instance-id "$instance_id" --query Status --output text 2>/dev/null || echo Pending)"
    case "$status" in
      Pending | InProgress | Delayed) continue ;;
    esac
    break
  done
  aws ssm get-command-invocation --region "$REGION" --command-id "$command_id" \
    --instance-id "$instance_id" --query StandardOutputContent --output text
  aws ssm get-command-invocation --region "$REGION" --command-id "$command_id" \
    --instance-id "$instance_id" --query StandardErrorContent --output text >&2
  echo "Status: $status"
  [[ "$status" == Success ]]
}

case "${1:-}" in
  host) host_main "${2:-check}" ;;
  check | apply) remote_main "$1" ;;
  *)
    echo "usage: $0 check|apply   (호스트에서 직접: $0 host check|apply)" >&2
    exit 2
    ;;
esac
