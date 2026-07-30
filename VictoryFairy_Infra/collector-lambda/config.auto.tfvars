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

# --- DB 적재 잡 (records/registrations) — 2026-07-29 조회값 ---
# 서브넷/SG는 infra 스택 소유. db_host 는 데이터 EC2 프라이빗 IP —
# 인스턴스 재생성(프라이빗 복귀 등) 시 여기와 k8s/30-external-data.yaml 둘 다 갱신.
db_subnet_ids    = ["subnet-05604c3c055f41298"] # dev-private-ap-northeast-2a (NAT·노드와 동일 AZ)
db_vpc_id        = "vpc-0ff40bff9268c9684"
db_ingress_sg_id = "sg-0b3cc8a90034605e2" # victoryfairy-mysql-dev-sg
db_host          = "10.0.0.14"            # = terraform output mysql_private_ip (2026-07-27~)
db_name          = "victoryfairy"
db_user          = "vf_collector"
