terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  # S3 원격 백엔드 (환경별 key 분리). 최초 1회 백엔드용 S3 버킷 + DynamoDB 락
  # 테이블을 만든 뒤 아래 주석을 해제하고 `terraform init` 하세요.
  # backend "s3" {
  #   bucket         = "victoryfairy-tfstate"
  #   key            = "dev/terraform.tfstate"
  #   region         = "ap-northeast-2"
  #   dynamodb_table = "victoryfairy-tflock"
  #   encrypt        = true
  # }
}
