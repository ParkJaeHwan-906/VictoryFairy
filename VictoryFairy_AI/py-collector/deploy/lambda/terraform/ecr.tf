resource "aws_ecr_repository" "this" {
  name                 = var.name
  image_tag_mutability = "MUTABLE"
  force_delete         = true # allow `terraform destroy` even with images present
  tags                 = var.tags

  image_scanning_configuration {
    scan_on_push = true
  }
}

# Build the container image and push it to ECR. Re-runs when the handler,
# Dockerfile, requirements, or the kbo_collector package changes.
resource "null_resource" "image" {
  triggers = {
    handler      = filemd5("${path.module}/../handler.py")
    dockerfile   = filemd5("${path.module}/../Dockerfile")
    requirements = filemd5("${path.module}/../requirements.txt")
    build_script = filemd5("${path.module}/../build_and_push.sh")
    core = sha1(join(",", [
      for f in fileset("${path.module}/../../../kbo_collector", "**/*.py") :
      filemd5("${path.module}/../../../kbo_collector/${f}")
    ]))
    # The Dockerfile also bakes in config/ (targets.yaml etc.), so a config change
    # must rebuild too — otherwise the image keeps stale targets (e.g. FMKorea left
    # in the Lambda crawl list even after it was moved out of targets.yaml).
    config = sha1(join(",", [
      for f in fileset("${path.module}/../../../config", "**") :
      filemd5("${path.module}/../../../config/${f}")
    ]))
  }

  provisioner "local-exec" {
    command = "${path.module}/../build_and_push.sh"
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
