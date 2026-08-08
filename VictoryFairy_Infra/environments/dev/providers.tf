provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "VictoryFairy"
      Environment = var.environment
      ManagedBy   = "Terraform"
    }
  }
}

# CloudFront 인증서 발급 전용. CloudFront 는 us-east-1 인증서만 받으므로 서울 프로바이더로는
# 만들 수 없다. 이 프로바이더로 만드는 것은 ACM 인증서 하나뿐이며, 그 외 리소스는 모두 서울이다.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = {
      Project     = "VictoryFairy"
      Environment = var.environment
      ManagedBy   = "Terraform"
    }
  }
}
