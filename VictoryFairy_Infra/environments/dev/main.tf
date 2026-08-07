# dev 환경: 모듈을 조립하는 루트. 리소스는 여기서 직접 선언하지 않는다.
# 모듈 구현이 끝나면 아래 블록의 주석을 해제한다.

# 다른 state 가 소유한 리소스의 ARN 조립용(locals.tf 의 collector_* 참고).
data "aws_caller_identity" "current" {}

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
  # fe 리포지토리는 2026-08-07 제거했다. FE 는 S3+CloudFront 가 서비스하므로 이미지를 pull 할
  # 주체(fe-app 파드)가 없어졌다(docs/fe-cdn-migration.md).
  # ⚠ 여기서 이름을 빼면 리포지토리가 destroy 된다. 이 모듈은 force_delete 를 켜지 않으므로
  #   이미지가 남아 있으면 RepositoryNotEmptyException 으로 apply 가 실패한다 —
  #   aws ecr batch-delete-image 로 먼저 비워야 한다(fe 는 그렇게 처리했다).
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
  # ⚠ 두 값은 짝이다 — 배치를 키우면 출력 상한도 함께 키워야 판정이 잘리지 않는다.
  bedrock_batch_post_size   = var.bedrock_batch_post_size
  bedrock_max_output_tokens = var.bedrock_max_output_tokens
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

  # us-east-1 은 CloudFront 인증서 발급 전용(CloudFront 는 서울 인증서를 거부한다).
  providers = {
    aws           = aws
    aws.us_east_1 = aws.us_east_1
  }

  domain_name       = var.domain_name
  cluster_name      = module.eks.cluster_name
  oidc_provider_arn = module.eks.oidc_provider_arn
  oidc_provider_url = module.eks.oidc_provider_url

  # CloudFront 가 ALB 오리진에 HTTPS 로 붙을 때 제시받는 이름. 전용 인증서를 따로 발급한다 —
  # apex 인증서의 SAN 으로 넣으면 인증서가 교체되고, 그것을 물고 있는 운영 ALB 리스너 때문에
  # 옛 인증서 삭제가 거부된다(modules/dns/main.tf §2-1). apex 인증서는 손대지 않는다.
  origin_host = local.api_origin_host

  # Mailjet 이메일 발신 도메인 인증 레코드. DKIM·검증만 지금 등록(ExternalDNS/apex와 무충돌).
  # SPF(mailjet_spf_value)는 apex TXT ↔ ExternalDNS 소유권 TXT 충돌로 보류 — 미주입=미생성.
  mailjet_dkim_value         = "k=rsa; p=MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAxm06i102hkoIV0UEihUmZjbLFVK+tYrU2JUKAOD8sSkKEUIXSXCdTe4dUCcSGoJAzf9NwuvNhChDzT8aQCBtpWzQlmAPyljd7Pkb5jsOyExoCz9vP/9pKvCTun8OIl7rGtv7mIiT5tIiSFl4dJdHzVWFPnCcA+IK/agQocbWymeRWKfsnP7Z/pqz3YERNbs10rIT11RsW09eBvqKiU8V008tkBtd43jcTMnuc0NWp2ZItHVQ9Ha8tz4dH+xI8JzPjD+wPnjlRKl4lJlo4im298RyE6GQjf07vzCa45L9pk4C8gZUA8a73SX462HyPrrfEXAdX324Wgi+vNU9CUhRGQIDAQAB"
  mailjet_verification_name  = "mailjet._bc2f75b5"
  mailjet_verification_value = "bc2f75b58109420e2abf5666cdeff8f5"
}

# FE 정적 호스팅 — S3(원본) + CloudFront(단일 진입점).
# CloudFront 가 /api/*·/rt/* 를 ALB 로, 나머지를 S3 로 갈라 보내므로 FE·API 가 같은 오리진으로 남는다.
# 전환 절차·롤백은 docs/fe-cdn-migration.md.
module "cdn" {
  source = "../../modules/cdn"

  name_prefix = local.cluster_name
  domain_name = var.domain_name

  # ⚠ 서울 인증서(module.dns.certificate_arn)가 아니다 — CloudFront 는 us-east-1 만 받는다.
  certificate_arn = module.dns.cloudfront_certificate_arn

  origin_domain_name = local.api_origin_host
  api_path_patterns  = local.api_path_patterns
  route53_zone_id    = module.dns.zone_id

  # 실서비스 전환 스위치. false 인 동안은 트래픽이 그대로 ALB 로 가고 CloudFront 는 배포
  # 도메인으로만 접근된다 — 검증을 마친 뒤 true 로 바꿔 apex 를 옮긴다(문서 §4 2단계).
  attach_apex_alias = var.fe_attach_apex_alias
}

# 상시 감시 — 배포 스모크(수십 초)가 닫힌 뒤를 맡는다.
# EventBridge → 점검 Lambda → 커스텀 지표 → 알람 → SNS → 대응 Lambda(FE 롤백·Slack·티켓).
# ⚠ FE 알람만 롤백을 유발한다. BE(user·quiz) 알람은 알림만 보낸다 — 원인이 BE 인데 FE 를
#   되돌리면 정상 FE 만 잃는다. 상세는 docs/fe-release-rollback.md.
module "fe_watchdog" {
  source = "../../modules/fe-watchdog"

  name_prefix = local.cluster_name

  # ⚠ CloudFront 배포 도메인이 아니라 apex 다. Host 헤더가 ALB 규칙과 맞아야 /api 점검이 성립한다.
  site_url = "https://${var.domain_name}"

  fe_bucket_name = module.cdn.bucket_name
  fe_bucket_arn  = module.cdn.bucket_arn

  # 키가 지표 차원(Target)이 되어 Slack 알림에 어느 모듈이 죽었는지 그대로 드러난다.
  # 경로는 BE 의 context-path 와 문자 그대로 일치해야 한다(/api·/rt).
  api_targets = {
    user = "/api/actuator/health/readiness"
    quiz = "/rt/actuator/health/readiness"
  }

  # 5분 주기 × 연속 2회 = 최대 10분 내 감지. 1회로 낮추면 단발 흔들림에 롤백한다.
  schedule_expression  = "rate(5 minutes)"
  alarm_period_seconds = 300
  datapoints_to_alarm  = 2

  # 시크릿은 코드에 두지 않는다. SSM SecureString 을 콘솔/CLI 로 넣고 이름만 참조한다.
  # 파라미터가 없으면 해당 기능(Slack 알림 / 티켓)만 생략되고 롤백은 계속 동작한다.
  slack_webhook_param = "/victoryfairy/dev/slack-webhook-url"
  github_token_param  = "/victoryfairy/dev/github-token"
  github_repo         = "ParkJaeHwan-906/VictoryFairy"

  # 장애 알림에서 호출할 사람. ⚠ 표시 이름(@박재환)은 알림을 울리지 않으므로 사용자 ID 여야 한다.
  #   박재환 · 소태호 · 손동현
  mention_user_ids = ["U0BGJAW7TGR", "U0B5RBDPN1K", "U0BGD4H2W2H"]
}

module "security" {
  source = "../../modules/security"

  name_prefix  = local.cluster_name
  cluster_name = module.eks.cluster_name # 출력 참조로 의존성 형성(Access Entry 는 클러스터 이후)

  # FE 배포 경로가 ECR+kubectl 에서 S3+CloudFront 로 바뀌면서 CI 에 필요해진 권한.
  fe_bucket_arn       = module.cdn.bucket_arn
  fe_distribution_arn = module.cdn.distribution_arn

  # CI(GitHub Actions) keyless 배포: 이 레포의 지정 브랜치 워크플로만 역할을 맡는다.
  github_repository   = "ParkJaeHwan-906/VictoryFairy"
  github_allowed_refs = ["main", "dev_infra"] # dev_infra 는 워크플로 테스트용 — 안정화 후 제거

  # 수집기 리포지토리는 infra 소유가 아니지만(locals.tf 참고) CI 가 여기에도 push 한다.
  ecr_repository_arns = concat(
    values(module.ecr.repository_arns),
    [local.collector_ecr_repository_arn],
  )
  deploy_namespaces = ["victoryfairy"]

  # 이미지별로 워크플로가 하나씩 있고, 각자 자기 이미지를 쓰는 함수만 갱신한다.
  #   victoryfairy-pipeline → refine-pattern·refine-bedrock (deploy-ai.yml)
  #   kbo-collector         → kbo-collector·kbo-collector-db (deploy-collector.yml)
  lambda_function_arns = concat([
    module.refine_pipeline.pattern_function_arn,
    module.refine_pipeline.bedrock_function_arn,
  ], local.collector_function_arns)
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
