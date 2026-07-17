# dev 환경: 모듈을 조립하는 루트. 리소스는 여기서 직접 선언하지 않는다.
# 모듈 구현이 끝나면 아래 블록의 주석을 해제한다.

# module "network" {
#   source = "../../modules/network"
#
#   environment          = var.environment
#   vpc_cidr             = var.vpc_cidr
#   azs                  = var.azs
#   public_subnet_cidrs  = ["10.0.0.0/24", "10.0.1.0/24"]
#   private_subnet_cidrs = ["10.0.10.0/24", "10.0.11.0/24"]
# }

# module "eks" {
#   source = "../../modules/eks"
#
#   environment         = var.environment
#   cluster_name        = "victoryfairy-${var.environment}"
#   cluster_version     = "1.30"
#   vpc_id              = module.network.vpc_id
#   private_subnet_ids  = module.network.private_subnet_ids
#   node_instance_types = ["t3.medium"]
#   node_scaling = {
#     min_size     = 2 # 다중 AZ 최소 2노드
#     desired_size = 2
#     max_size     = 5 # Cluster Autoscaler 상한
#   }
# }

# module "mysql_ec2" {
#   source = "../../modules/mysql-ec2"
#
#   environment           = var.environment
#   vpc_id                = module.network.vpc_id
#   subnet_id             = module.network.private_subnet_ids[0]
#   instance_type         = "t3.small"
#   allowed_source_sg_ids = [module.eks.node_security_group_id] # EKS 노드만 3306 허용
#   backup_s3_bucket      = var.backup_s3_bucket                # 일 단위 S3 백업
# }
