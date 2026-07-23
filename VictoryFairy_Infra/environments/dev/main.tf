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
  cluster_version = "1.30"

  # 컨트롤플레인: 프라이빗 서브넷 2 AZ(2a+2c) 모두 — EKS 2 AZ 요건 충족.
  cluster_subnet_ids = module.network.private_subnet_ids
  # 노드: 운영 AZ(2a) 프라이빗 서브넷에만 집중(2c는 예비). azs[0] = 2a.
  node_subnet_ids = [module.network.private_subnet_ids_by_az[var.azs[0]]]

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

  name_prefix      = "victoryfairy"
  repository_names = ["user", "quiz"] # BE Gradle 모듈과 1:1 (Dockerfile ARG MODULE)
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
}

module "mysql_ec2" {
  source = "../../modules/mysql-ec2"

  environment   = var.environment
  vpc_id        = module.network.vpc_id
  subnet_id     = module.network.private_subnet_ids_by_az[var.azs[0]] # 2a(운영 AZ)
  instance_type = "t3.small"

  # 3306 ← user·quiz·batch, 6379 ← user·quiz 만. 현재 eks 는 공용 노드 SG 하나라
  # 두 맵에 같은 SG가 들어간다. 노드그룹이 전용 SG로 분리되면 redis 맵에서 batch 를 제외.
  # (map 사용 이유: SG ID가 apply-time unknown이라 for_each 키로 못 씀 → 정적 키로 감싼다)
  mysql_ingress_sg_ids = { eks_nodes = module.eks.node_security_group_id }
  redis_ingress_sg_ids = { eks_nodes = module.eks.node_security_group_id }

  backup_s3_bucket = var.backup_s3_bucket # 일 단위 mysqldump S3 백업

  # 현재 계정(ISB 샌드박스)은 조직 SCP가 dlm:TagResource 를 명시적 거부하여
  # DLM 스냅샷 정책 생성이 불가. 백업은 mysqldump→S3 크론으로만 수행한다.
  # SCP 제약이 없는 계정으로 이전 시 이 줄을 제거해 스냅샷 병행을 복원할 것.
  enable_dlm_snapshot = false
}
