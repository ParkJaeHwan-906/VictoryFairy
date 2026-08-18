# ecr 모듈: 앱 컨테이너 이미지 저장소 (EKS 파드가 pull 하는 원천)
#
#   - EKS 노드는 AmazonEC2ContainerRegistryReadOnly(eks 모듈)로 pull 권한을 이미 가진다.

resource "aws_ecr_repository" "this" {
  for_each = toset(var.repository_names)

  name = "${var.name_prefix}-${each.value}"

  # 같은 태그 덮어쓰기 금지 — 배포된 태그가 가리키는 이미지가 바뀌는 사고 방지.
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-${each.value}"
  })
}

resource "aws_ecr_lifecycle_policy" "this" {
  for_each = aws_ecr_repository.this

  repository = each.value.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "최근 ${var.keep_image_count}개 이미지만 보관"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = var.keep_image_count
        }
        action = { type = "expire" }
      }
    ]
  })
}
