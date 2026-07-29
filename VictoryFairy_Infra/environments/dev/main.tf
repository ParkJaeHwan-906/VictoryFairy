# dev 환경: 모듈을 조립하는 루트. 리소스는 여기서 직접 선언하지 않는다.
# 모듈 구현이 끝나면 아래 블록의 주석을 해제한다.

module "network" {
  source = "../../modules/network"

  environment          = var.environment
  cluster_name         = local.cluster_name # 서브넷 EKS 발견 태그 ↔ eks 모듈과 동일
  vpc_cidr             = var.vpc_cidr
  azs                  = var.azs
  public_subnet_cidrs  = ["10.0.0.0/24", "10.0.1.0/24"]   # [2a, 2c] — azs 순서와 일치
  private_subnet_cidrs = ["10.0.10.0/24", "10.0.11.0/24"] # [2a, 2c] — azs 순서와 일치
}

module "eks" {
  source = "../../modules/eks"

  environment     = var.environment
  cluster_name    = local.cluster_name # 서브넷 EKS 발견 태그 ↔ network 모듈과 동일
  cluster_version = "1.35"             # 1.30(연장지원) 탈출 여정, 목표 1.35. 단일 apply로 CP+노드 함께 업그레이드(애드온은 apply 후 CLI).

  # 컨트롤플레인: 프라이빗 서브넷 2 AZ(2a+2c) 모두 — EKS 2 AZ 요건 충족.
  cluster_subnet_ids = module.network.private_subnet_ids
  # 노드: 운영 AZ(2a) 프라이빗 서브넷에만 집중(2c는 예비). azs[0] = 2a.
  node_subnet_ids = [module.network.private_subnet_ids_by_az[var.azs[0]]]

  # 노드 SSH(pem) — 계정에 이미 존재하는 EC2 키페어 재사용(Terraform 외부 생성 자원).
  # 접속은 MySQL EC2와 동일하게 SSM 터널 경유 SSH. ⚠ 기존 노드그룹 교체(재생성) 유발(의도됨).
  node_ssh_key_name = "VictoryFairy"

  # 노드그룹 2개. labels/taints 값은 k8s 매니페스트의 nodeSelector/toleration 과
  # 반드시 일치해야 한다(불일치 시 파드 Pending).
  node_groups = {
    # app: user·quiz 공용 노드풀 — 같은 노드에 동거(taint 격리 없음).
    #   파드 상한은 매니페스트가 담당(user replicas 2 고정, quiz HPA max 4).
    #   노드 자원이 차서 파드가 Pending 되면 Cluster Autoscaler 가 수평 확장.
    #   (설계 변경 2026-07: 기존 user/quiz 분리 노드그룹 → 공용 풀 통합)
    app = {
      instance_types     = ["t3.medium"]
      capacity_type      = "ON_DEMAND"
      min_size           = 1
      desired_size       = 1
      max_size           = 4 # 전체 파드 requests(최대 ~1.7CPU/3Gi) 대비 여유 상한
      labels             = { workload = "app" }
      cluster_autoscaler = true
      taints             = {}
    }
    # batch: Spot, 평소 0대(비용 $0), CronJob 시각에만 0→N→0.
    batch = {
      instance_types     = ["m5.xlarge"]
      capacity_type      = "SPOT"
      min_size           = 0
      desired_size       = 0
      max_size           = 6
      labels             = { workload = "batch" }
      cluster_autoscaler = true
      taints = {
        workload = { key = "workload", value = "batch", effect = "NO_SCHEDULE" }
      }
    }
  }
}

module "ecr" {
  source = "../../modules/ecr"

  name_prefix = "victoryfairy"
  # user/quiz 는 BE Gradle 모듈과 1:1 (Dockerfile ARG MODULE).
  # pipeline 은 정제 러너 이미지 — 패턴·Bedrock Lambda 가 같은 이미지를 공유한다(ARCHITECTURE §4).
  repository_names = ["user", "quiz", "pipeline"]
}

# ─────────────────────────────────────────────────────────────────────────────
# 정제 파이프라인 (서버리스) — ARCHITECTURE §4
# ─────────────────────────────────────────────────────────────────────────────
# 크롤(Lambda kbo-collector, Terraform 관리 밖) → S3 community/ → [S3 이벤트]
#   → pattern Lambda → SQS → [batch_size] → bedrock Lambda → S3 validation/bedrock/
#
# ⚠ apply 선행 조건: victoryfairy-pipeline 리포지토리에 var.refine_image_tag 태그가
#   push 돼 있어야 한다. 이미지가 없으면 Lambda 생성에서 실패한다(plan 은 통과).
#   이미지는 VictoryFairy_AI 의 pipeline/Dockerfile 로 빌드한다.
module "refine_pipeline" {
  source = "../../modules/refine-pipeline"

  name_prefix       = local.cluster_name # victoryfairy-dev
  crawl_bucket_name = var.crawl_bucket_name

  pipeline_repository_url = module.ecr.repository_urls["pipeline"]
  image_tag               = var.refine_image_tag

  # SQS batch_size 와 Lambda 의 BEDROCK_BATCH_POST_SIZE 를 함께 움직인다(같은 값이어야 한다).
  bedrock_batch_post_size = var.bedrock_batch_post_size
}

# AWS Load Balancer Controller 용 IRSA. 컨트롤러 파드는 Helm 설치(runbook)하고,
# 이 역할 ARN 을 SA 어노테이션에 지정한다. Ingress(k8s/22-ingress.yaml)가 ALB 를 프로비저닝.
module "alb" {
  source = "../../modules/alb"

  name_prefix       = local.cluster_name
  oidc_provider_arn = module.eks.oidc_provider_arn
  oidc_provider_url = module.eks.oidc_provider_url
}

# 퍼블릭 DNS(Route53) + TLS(ACM) + ExternalDNS IRSA.
# apply 후 name_servers 를 레지스트라에 등록해야 존이 활성화되고 ACM 검증이 완료된다(runbook).
module "dns" {
  source = "../../modules/dns"

  domain_name       = var.domain_name
  cluster_name      = module.eks.cluster_name
  oidc_provider_arn = module.eks.oidc_provider_arn
  oidc_provider_url = module.eks.oidc_provider_url

  # Mailjet 이메일 발신 도메인 인증 레코드. DKIM·검증만 지금 등록(ExternalDNS/apex와 무충돌).
  # SPF(mailjet_spf_value)는 apex TXT ↔ ExternalDNS 소유권 TXT 충돌로 보류 — 미주입=미생성.
  mailjet_dkim_value         = "k=rsa; p=MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAxm06i102hkoIV0UEihUmZjbLFVK+tYrU2JUKAOD8sSkKEUIXSXCdTe4dUCcSGoJAzf9NwuvNhChDzT8aQCBtpWzQlmAPyljd7Pkb5jsOyExoCz9vP/9pKvCTun8OIl7rGtv7mIiT5tIiSFl4dJdHzVWFPnCcA+IK/agQocbWymeRWKfsnP7Z/pqz3YERNbs10rIT11RsW09eBvqKiU8V008tkBtd43jcTMnuc0NWp2ZItHVQ9Ha8tz4dH+xI8JzPjD+wPnjlRKl4lJlo4im298RyE6GQjf07vzCa45L9pk4C8gZUA8a73SX462HyPrrfEXAdX324Wgi+vNU9CUhRGQIDAQAB"
  mailjet_verification_name  = "mailjet._bc2f75b5"
  mailjet_verification_value = "bc2f75b58109420e2abf5666cdeff8f5"
}

module "security" {
  source = "../../modules/security"

  name_prefix  = local.cluster_name
  cluster_name = module.eks.cluster_name # 출력 참조로 의존성 형성(Access Entry 는 클러스터 이후)

  # CI(GitHub Actions) keyless 배포: 이 레포의 지정 브랜치 워크플로만 역할을 맡는다.
  github_repository   = "ParkJaeHwan-906/VictoryFairy"
  github_allowed_refs = ["main", "dev_infra"] # dev_infra 는 워크플로 테스트용 — 안정화 후 제거

  ecr_repository_arns = values(module.ecr.repository_arns)
  deploy_namespaces   = ["victoryfairy"]

  # 정제 파이프라인 Lambda 2개는 같은 pipeline 이미지를 공유한다 — CI 가 이미지를
  # push 한 뒤 두 함수를 함께 갱신한다(.github/workflows/deploy-ai.yml).
  lambda_function_arns = [
    module.refine_pipeline.pattern_function_arn,
    module.refine_pipeline.bedrock_function_arn,
  ]
}

module "mysql_ec2" {
  source = "../../modules/mysql-ec2"

  environment = var.environment
  vpc_id      = module.network.vpc_id

  # 서브넷 배치는 '개발자 직접 접속을 쓰는지'에 따라 갈린다(둘 다 운영 AZ = 2a).
  #   - mysql_public_access_cidrs 가 비어있음(기본·권장) → 프라이빗 서브넷 + SSM 전용.
  #   - 값이 있음 → 퍼블릭 서브넷 + EIP. 보조 ENI 방식으로는 인바운드가 오지 않아
  #     (modules/mysql-ec2/main.tf 주석 참조) 인스턴스 자체를 퍼블릭에 둬야 한다.
  #
  # ⚠ 이 분기를 뒤집으면 인스턴스가 '재생성'된다. 배포 안정화 후 프라이빗으로 되돌릴 때의
  #   절차(데이터 보존 확인 + k8s Endpoints IP 갱신)는 docs/COMMANDS.md 를 따를 것.
  subnet_id = length(var.mysql_public_access_cidrs) > 0 ? module.network.public_subnet_ids_by_az[var.azs[0]] : module.network.private_subnet_ids_by_az[var.azs[0]]

  instance_type = "t3.small"

  # 3306 ← user·quiz·batch, 6379 ← user·quiz 만. 현재 eks 는 공용 노드 SG 하나라
  # 두 맵에 같은 SG가 들어간다. 노드그룹이 전용 SG로 분리되면 redis 맵에서 batch 를 제외.
  # (map 사용 이유: SG ID가 apply-time unknown이라 for_each 키로 못 씀 → 정적 키로 감싼다)
  mysql_ingress_sg_ids = { eks_nodes = module.eks.node_security_group_id }
  redis_ingress_sg_ids = { eks_nodes = module.eks.node_security_group_id }

  backup_s3_bucket = var.backup_s3_bucket # 일 단위 mysqldump S3 백업

  # 개발자 PC 직접 접속(옵션): 허용 CIDR 에서만 아래 포트를 연다.
  # 비우면 퍼블릭 인입 규칙·EIP·퍼블릭 IP 가 사라지고 프라이빗 + SSM 전용으로 돌아간다.
  public_access_cidrs = var.mysql_public_access_cidrs

  # dev_db(22/3306/6379)와 동일하게 Redis 도 함께 연다(사용자 결정 2026-07-27).
  # ⚠ 이 호스트의 Redis 는 requirepass 없이 뜬다(user_data §5) → 허용 CIDR 안에서는
  #   인증 없이 서비스 브로커/이메일 TTL 키에 접근된다. CIDR 을 /32 보다 넓히지 말 것.
  #   22(SSH)는 열지 않는다 — 이 인스턴스는 키페어가 없고 셸은 SSM 으로 붙는다.
  public_access_ports = [3306, 6379]

  # 현재 계정(ISB 샌드박스)은 조직 SCP가 dlm:TagResource 를 명시적 거부하여
  # DLM 스냅샷 정책 생성이 불가. 백업은 mysqldump→S3 크론으로만 수행한다.
  # SCP 제약이 없는 계정으로 이전 시 이 줄을 제거해 스냅샷 병행을 복원할 것.
  enable_dlm_snapshot = false
}

# dev 전용 DB(비 프로덕션): 프로덕션 mysqldump S3 백업을 매일 restore 로 받아 데이터를
# 갱신하는 퍼블릭 MySQL+Redis(fresh) EC2. 개발자가 로컬에서 직접 붙어 쓰기 위한 용도라
# 프라이빗이 아닌 '퍼블릭 서브넷 + 퍼블릭 IP', 인입은 dev_db_allowed_cidr 하나에서만 연다.
#
# 조건부 생성: dev_db_allowed_cidr 가 빈 값이면 count=0 → 미생성(plan 에도 안 뜬다).
# 사용자가 자신의 IP CIDR 을 terraform.tfvars 에 넣어야 비로소 생성된다.
module "dev_db" {
  source = "../../modules/dev-db"

  count = length(var.dev_db_allowed_cidrs) > 0 ? 1 : 0

  environment   = var.environment
  vpc_id        = module.network.vpc_id
  subnet_id     = module.network.public_subnet_ids_by_az[var.azs[0]] # 2a(운영 AZ) 퍼블릭
  allowed_cidrs = var.dev_db_allowed_cidrs

  # restore 원본 = 프로덕션 mysqldump 백업 버킷(읽기 전용).
  backup_s3_bucket = var.backup_s3_bucket

  # 프로덕션 mysql-ec2 와 '동일한' 비밀번호 파라미터여야 --all-databases 복원 후에도
  # root 비번이 일관된다. mysql_ec2 모듈은 이 값을 default 로 사용한다(무명시).
  mysql_root_password_ssm_parameter_name = "/victoryfairy/mysql/root-password"

  use_eip = var.dev_db_use_eip # true 면 stop/start 후에도 퍼블릭 IP 고정(EIP)
}
