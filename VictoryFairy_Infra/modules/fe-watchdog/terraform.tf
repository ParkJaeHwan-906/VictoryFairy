terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }

    # ⚠ 이 저장소의 다른 Lambda 는 모두 컨테이너 이미지(package_type = "Image")다. 여기만 zip 인
    #   이유: 감시 함수는 표준 라이브러리 + boto3 만 쓰는 수십 줄짜리라, ECR 리포지토리와 이미지
    #   빌드 파이프라인을 새로 두는 비용이 함수 자체보다 크다. 의존성이 늘어나면 그때 컨테이너로
    #   옮기는 것이 맞다.
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.4"
    }
  }
}
