resource "aws_ecr_repository" "this" {
  name                 = var.name
  image_tag_mutability = "MUTABLE"
  force_delete         = true # allow `terraform destroy` even with images present
  tags                 = var.tags

  image_scanning_configuration {
    scan_on_push = true
  }
}

# 이미지 빌드 원천 = py-collector 소스(dev_ai 소유, 이 리포 dev_infra 브랜치에는 없음).
# 기본 경로는 VictoryFairy_AI 가 나란히 있는 체크아웃(main 등) 기준 상대경로 —
# dev_infra 단독 체크아웃에서는 collector_src 변수로 절대경로를 지정해야 plan 이 된다.
locals {
  collector_src = var.collector_src != "" ? var.collector_src : "${path.module}/../../VictoryFairy_AI/py-collector"
  lambda_src    = "${local.collector_src}/deploy/lambda"
}

# Build the container image and push it to ECR. Re-runs when the handler,
# Dockerfile, requirements, or the kbo_collector package changes.
resource "null_resource" "image" {
  triggers = {
    handler      = filemd5("${local.lambda_src}/handler.py")
    dockerfile   = filemd5("${local.lambda_src}/Dockerfile")
    requirements = filemd5("${local.lambda_src}/requirements.txt")
    build_script = filemd5("${local.lambda_src}/build_and_push.sh")
    core = sha1(join(",", [
      for f in fileset("${local.collector_src}/kbo_collector", "**/*.py") :
      filemd5("${local.collector_src}/kbo_collector/${f}")
    ]))
    # The Dockerfile also bakes in config/ (targets.yaml etc.), so a config change
    # must rebuild too — otherwise the image keeps stale targets (e.g. FMKorea left
    # in the Lambda crawl list even after it was moved out of targets.yaml).
    config = sha1(join(",", [
      for f in fileset("${local.collector_src}/config", "**") :
      filemd5("${local.collector_src}/config/${f}")
    ]))
  }

  provisioner "local-exec" {
    command = "${local.lambda_src}/build_and_push.sh"
    environment = {
      ECR_URL  = aws_ecr_repository.this.repository_url
      REGION   = var.region
      TAG      = var.image_tag
      PLATFORM = var.architecture == "arm64" ? "linux/arm64" : "linux/amd64"
    }
  }

  depends_on = [aws_ecr_repository.this]
}

# Resolve the digest of the freshly-pushed image so the Lambda pins to an
# immutable digest rather than the mutable :latest tag. `depends_on` defers this
# read until after null_resource.image pushes, so a code change (new digest)
# flows into image_uri and `terraform apply` updates the function in the SAME
# run. Pinning to :latest never works: the string doesn't change, so Terraform
# sees no diff and leaves the old image running.
data "aws_ecr_image" "deployed" {
  repository_name = aws_ecr_repository.this.name
  image_tag       = var.image_tag
  depends_on      = [null_resource.image]
}
