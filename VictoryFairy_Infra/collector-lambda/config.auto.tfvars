# 비밀이 아닌 스택 설정 — 커밋되는 파일 (CI 와 로컬이 같은 값을 쓴다).
# 비밀 2개는 여기 두지 않는다:
#   db_password, pii_salt → CI: GitHub Secrets(TF_VAR_*) / 로컬: terraform.tfvars
# *.auto.tfvars 는 terraform.tfvars 보다 나중에 로드되어 같은 키를 덮어쓴다.
region           = "ap-northeast-2"
name             = "kbo-collector"
data_bucket_name = "victoryfairy-crawl-dev"
architecture     = "arm64" # native on Apple Silicon, cheaper

# Match the local .env salt so masked comment-authors are consistent across
# local runs and the Lambda (same author -> same token).

# Popular-only community crawl tuning (see kbo_collector config).
community_schedule    = "rate(10 minutes)"
community_concurrency = 3
community_delay_ms    = 400

# Game data (schedule/result/relay) once a day at 03:00 KST (18:00 UTC).
game_schedule = "cron(0 18 * * ? *)"

# --- 퀴즈 원천 잡 게이트 ---
# 2026-08-07 에 켰다. 선행 조건 둘을 실제로 확인한 뒤다(README "퀴즈 원천 잡 컷오버"):
#   1. 이미지 배포 — sha256:88c074e2 로 두 함수 갱신(15:31 KST). 그 전 이미지
#      (edc3de25)엔 kbo_records/game_schedule/export 분기가 없었다.
#   2. 응답에 결과 키 확인 — kboRecords{loaded:8}, gameSchedule:0(당일 5경기 전부
#      취소라 0 이 정상), exported:499(game_result), exported:558(player_profile).
#      모르는 job 은 StatusCode 200 에 빈 summary 라 이 확인 없이는 구분이 안 된다.
# 버킷 일원화(3번)도 같은 날 끝났다 — 루틴 S3_BUCKET → -dev, 과거분 65건 이관.
quiz_source_jobs_enabled = true

# --- KBO 취소 사유 잡 게이트 ---
# 2026-08-10 에 켰다. 선행 조건 둘을 실제로 확인한 뒤다(절차는 quiz_source_jobs_enabled 와 동일):
#   1. games.cancel_reason 컬럼 — dev_be #281 머지 후 user 앱 재기동으로 생성됐다.
#      운영 DB 실측: cancel_reason varchar(50) YES.
#   2. cancel_reasons 잡을 아는 이미지 — dev_ai #283 머지 후 06:47 UTC 에 두 함수 갱신.
#      수동 호출 응답에 결과 키가 왔다: {"job":"cancel_reasons", ..., "cancelReasons": 30}.
#      모르는 job 은 StatusCode 200 에 빈 summary 라 이 확인 없이는 구분이 안 된다.
# 같은 날 8월 취소 30건이 games.cancel_reason 에 전부 반영된 것까지 확인했다
# (8/01~8/09 취소 경기 커버리지 100%).
#
# ⚠ 사유가 붙으려면 그 경기의 games 행이 먼저 있어야 한다. 8/07 이전 취소 경기는
#   행 자체가 없어 처음엔 15/30 만 붙었고, games_sync 백필(8/01~8/06)로 25행을 만든
#   뒤에야 30/30 이 됐다. 룰 순서(00:30 games_sync -> 01:00 cancel_reasons)가 이 선후를
#   지키는 장치다.
cancel_reasons_enabled = true

# --- DB 적재 잡 (records/registrations) — 2026-07-29 조회값 ---
# 서브넷/SG는 infra 스택 소유. db_host 는 데이터 EC2 프라이빗 IP —
# 인스턴스 재생성(프라이빗 복귀 등) 시 여기와 k8s/30-external-data.yaml 둘 다 갱신.
db_subnet_ids    = ["subnet-05604c3c055f41298"] # dev-private-ap-northeast-2a (NAT·노드와 동일 AZ)
db_vpc_id        = "vpc-0ff40bff9268c9684"
db_ingress_sg_id = "sg-0b3cc8a90034605e2" # victoryfairy-mysql-dev-sg
db_host          = "10.0.0.14"            # = terraform output mysql_private_ip (2026-07-27~)
db_name          = "victoryfairy"
db_user          = "vf_collector"
