terraform {
  required_version = ">= 1.5"
  required_providers {
    aws    = { source = "hashicorp/aws", version = "~> 5.0" }
    random = { source = "hashicorp/random", version = "~> 3.5" }
  }

  # 원격 state — environments/dev 와 같은 버킷/락 테이블, 스택별 key 분리.
  # 로컬 state 에서 처음 전환하는 머신에서는 `terraform init -migrate-state`.
  backend "s3" {
    bucket         = "victoryfairy-tfstate"
    key            = "collector-lambda/terraform.tfstate"
    region         = "ap-northeast-2"
    dynamodb_table = "victoryfairy-tflock"
    encrypt        = true
  }
}

provider "aws" {
  region = var.region
}

data "aws_caller_identity" "current" {}
