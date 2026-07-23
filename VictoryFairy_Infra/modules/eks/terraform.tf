terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    # OIDC(IRSA) 프로바이더의 지문(thumbprint)을 클러스터 OIDC issuer의 TLS
    # 인증서에서 계산하기 위해 필요하다. 프로바이더 설정(config)은 필요 없다.
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }
}
