terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }

  # S3 원격 백엔드 (환경별 key 분리). 버킷·락 테이블은 2026-07-23 수동 생성됨
  # (버저닝·SSE-S3 암호화·퍼블릭 차단). 기존 로컬 state 를 가진 머신에서는
  # `terraform init -migrate-state` 로 state 를 S3 로 이관한 뒤 사용한다.
  backend "s3" {
    bucket         = "victoryfairy-tfstate"
    key            = "dev/terraform.tfstate"
    region         = "ap-northeast-2"
    dynamodb_table = "victoryfairy-tflock"
    encrypt        = true
  }
}
