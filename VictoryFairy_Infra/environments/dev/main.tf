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

  # 노드그룹 3개. labels/taints 값은 앱 레포 k8s 매니페스트의 nodeSelector/toleration 과
  # 반드시 일치해야 한다(불일치 시 파드 Pending).
  node_groups = {
    # user: 안정 규모, 라벨로 지정. Cluster Autoscaler 없음.
    user = {
      instance_types     = ["t3.medium"]
      capacity_type      = "ON_DEMAND"
      min_size           = 2
      desired_size       = 2
      max_size           = 3
      labels             = { workload = "user" }
      cluster_autoscaler = false
      taints             = {}
    }
    # quiz: taint 로 전용 격리 + 오토스케일(HPA→Cluster Autoscaler).
    quiz = {
      instance_types     = ["t3.medium"]
      capacity_type      = "ON_DEMAND"
      min_size           = 2
      desired_size       = 2
      max_size           = 8
      labels             = { workload = "quiz" }
      cluster_autoscaler = true
      taints = {
        workload = { key = "workload", value = "quiz", effect = "NO_SCHEDULE" }
      }
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
}
