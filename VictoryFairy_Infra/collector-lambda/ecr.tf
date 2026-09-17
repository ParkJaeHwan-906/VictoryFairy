resource "aws_ecr_repository" "this" {
  name                 = var.name
  image_tag_mutability = "MUTABLE"
  force_delete         = true # allow `terraform destroy` even with images present
  tags                 = var.tags

  image_scanning_configuration {
    scan_on_push = true
  }
}

# 여기서는 그 시점의 :latest 다이제스트를 읽어 함수 정의에 핀할 뿐이라, 이 스택의
# plan/apply 에 py-collector 소스가 필요 없다(dev_infra 단독 체크아웃에서 동작).
#
# - infra apply 는 그 시점 latest 로 함수를 맞춘다. CI 가 이미 최신으로 유지하므로
#   보통 no-op 이고, CI 의 코드 갱신이 중간에 실패했어도 apply 가 수렴시켜 준다.
# - 새 환경 부트스트랩: ECR 리포만 먼저 만들고(targeted apply) 이미지 CI 를 1회
#   돌린 뒤 전체 apply — 이미지가 하나도 없으면 이 data 조회가 실패한다.
data "aws_ecr_image" "deployed" {
  repository_name = aws_ecr_repository.this.name
  image_tag       = var.image_tag
}
