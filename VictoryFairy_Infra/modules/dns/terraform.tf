terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"

      # CloudFront 인증서는 반드시 us-east-1 에서 발급해야 한다(다른 리전 인증서는 거부된다).
      # 루트가 providers = { aws = aws, aws.us_east_1 = aws.us_east_1 } 로 넘겨준다.
      configuration_aliases = [aws.us_east_1]
    }
  }
}
