# dev-db 모듈: dev 전용 MySQL + (fresh) Redis EC2 자체 호스팅
# (프로덕션 modules/mysql-ec2 와 '별도' 모듈 — 원본 무수정. 별도 인스턴스/이름 네임스페이스.)
#
# 프로덕션과의 차이(ARCHITECTURE.md §3 대비):
#   - 네트워크: '퍼블릭' 서브넷 + 퍼블릭 IP(associate_public_ip_address). IMDSv2 강제 유지.
#   - 보안그룹: 22/3306/6379 를 'allowed_cidr 하나'에서만(EKS 노드 SG 소스 아님). 0.0.0.0/0 금지.
#   - IAM: S3 '읽기'(GetObject+ListBucket) + SSM Core + 비밀번호 조회. '쓰기 없음'(restore 전용).
#   - 데이터: 매일 최신 덤프를 restore 로 갱신. Redis 는 fresh(빈 상태, 복원 안 함).
#   - 데이터 EBS: dev 소모성이라 prevent_destroy 없음(원본은 prevent_destroy).

# ---------------------------------------------------------------------------
# Data sources
# ---------------------------------------------------------------------------
data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

# 인스턴스가 놓일 서브넷의 AZ — EBS 볼륨은 동일 AZ 에 생성해야 부착 가능.
data "aws_subnet" "this" {
  id = var.subnet_id
}

# 최신 Amazon Linux 2023 AMI (x86_64). latest 하드코딩 금지 → data source 조회.
data "aws_ami" "al2023" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-x86_64"]
  }

  filter {
    name   = "architecture"
    values = ["x86_64"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

# ---------------------------------------------------------------------------
# IAM: 인스턴스 역할 (SSM 접근 + 백업 S3 '읽기' + SSM 파라미터 조회) — 최소 권한.
#   ⚠ 프로덕션과 달리 s3:PutObject 는 부여하지 않는다(restore 전용, 쓰기 금지).
# ---------------------------------------------------------------------------
data "aws_iam_policy_document" "assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "this" {
  name               = "${local.name}-role"
  assume_role_policy = data.aws_iam_policy_document.assume.json

  tags = merge(var.tags, {
    Name = "${local.name}-role"
  })
}

# SSM Session Manager 접근(세션·디버깅).
resource "aws_iam_role_policy_attachment" "ssm_core" {
  role       = aws_iam_role.this.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# restore 읽기 + 비밀번호 조회 인라인 정책 — action/resource ARN 명시, "*" 지양(SKILL §7).
data "aws_iam_policy_document" "instance" {
  # 최신 덤프 객체를 다운로드(읽기 전용). 정해진 버킷/prefix 하위로만.
  statement {
    sid       = "RestoreGetObject"
    actions   = ["s3:GetObject"]
    resources = [local.backup_object_arn]
  }

  # 최신 .sql.gz 를 찾기 위한 목록 조회 — 버킷 ARN 대상 + prefix 조건으로 최소화.
  statement {
    sid       = "RestoreListBucket"
    actions   = ["s3:ListBucket"]
    resources = [local.backup_bucket_arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["${var.backup_s3_prefix}*"]
    }
  }

  # MySQL root 비밀번호(SecureString)를 지정된 파라미터에서만 조회(프로덕션과 동일 파라미터).
  statement {
    sid       = "ReadMysqlRootPassword"
    actions   = ["ssm:GetParameter"]
    resources = ["arn:aws:ssm:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:parameter${var.mysql_root_password_ssm_parameter_name}"]
  }
}

resource "aws_iam_role_policy" "instance" {
  name   = "${local.name}-inline"
  role   = aws_iam_role.this.id
  policy = data.aws_iam_policy_document.instance.json
}

resource "aws_iam_instance_profile" "this" {
  name = "${local.name}-profile"
  role = aws_iam_role.this.name

  tags = merge(var.tags, {
    Name = "${local.name}-profile"
  })
}

# ---------------------------------------------------------------------------
# 보안그룹 — 인입은 allowed_cidr '하나'에서 오는 22/3306/6379 만. 0.0.0.0/0 금지.
# ---------------------------------------------------------------------------
resource "aws_security_group" "this" {
  name        = "${local.name}-sg"
  description = "dev MySQL/Redis host. Ingress 22/3306/6379 from a single allowed_cidr only."
  vpc_id      = var.vpc_id

  tags = merge(var.tags, {
    Name = "${local.name}-sg"
  })

  lifecycle {
    create_before_destroy = true
  }
}

# 22/3306/6379 ← allowed_cidr. for_each 로 포트 반복(SKILL §8).
resource "aws_vpc_security_group_ingress_rule" "allowed" {
  for_each = {
    ssh   = { port = 22, desc = "SSH from allowed_cidr" }
    mysql = { port = 3306, desc = "MySQL from allowed_cidr" }
    redis = { port = 6379, desc = "Redis from allowed_cidr" }
  }

  security_group_id = aws_security_group.this.id
  cidr_ipv4         = var.allowed_cidr
  from_port         = each.value.port
  to_port           = each.value.port
  ip_protocol       = "tcp"
  description       = each.value.desc

  tags = merge(var.tags, {
    Name = "${local.name}-${each.key}"
  })
}

# 아웃바운드 전체 허용 — 패키지 설치·restore S3 다운로드·SSM 엔드포인트 통신 등.
resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.this.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
  description       = "Allow all outbound (package/restore/SSM)"

  tags = merge(var.tags, {
    Name = "${local.name}-egress"
  })
}

# ---------------------------------------------------------------------------
# EC2 인스턴스 — '퍼블릭' 서브넷 + 퍼블릭 IP, IMDSv2 강제.
# ---------------------------------------------------------------------------
resource "aws_instance" "this" {
  ami                         = data.aws_ami.al2023.id
  instance_type               = var.instance_type
  subnet_id                   = var.subnet_id
  vpc_security_group_ids      = [aws_security_group.this.id]
  iam_instance_profile        = aws_iam_instance_profile.this.name
  associate_public_ip_address = true # dev: 퍼블릭 서브넷 배치 + 공인 IP 부여

  user_data = templatefile("${path.module}/templates/user_data.sh.tftpl", {
    data_volume_device_name                = var.data_volume_device_name
    backup_s3_bucket                       = var.backup_s3_bucket
    backup_s3_prefix                       = var.backup_s3_prefix
    aws_region                             = data.aws_region.current.name
    mysql_root_password_ssm_parameter_name = var.mysql_root_password_ssm_parameter_name
    innodb_buffer_pool_size                = var.innodb_buffer_pool_size
    redis_maxmemory                        = var.redis_maxmemory
    restore_cron                           = var.restore_cron
  })

  root_block_device {
    volume_type = "gp3"
    volume_size = var.root_volume_size_gb
    encrypted   = true

    tags = merge(var.tags, {
      Name = "${local.name}-root"
    })
  }

  metadata_options {
    http_tokens   = "required" # IMDSv2 강제
    http_endpoint = "enabled"
  }

  tags = merge(var.tags, {
    Name = local.name
  })

  lifecycle {
    # AMI most_recent 부동으로 인한 재생성 방지. 의도적 OS 교체는 이 줄을 임시 제거.
    ignore_changes = [ami]
  }
}

# ---------------------------------------------------------------------------
# 데이터 EBS (gp3) — MySQL datadir 영속용. dev 는 매일 restore 로 갱신되는 소모성
#   데이터라 prevent_destroy 를 걸지 않는다(원본 mysql-ec2 는 prevent_destroy).
# ---------------------------------------------------------------------------
resource "aws_ebs_volume" "data" {
  availability_zone = data.aws_subnet.this.availability_zone
  size              = var.data_volume_size_gb
  type              = "gp3"
  encrypted         = true

  tags = merge(var.tags, {
    Name = "${local.name}-data"
  })
}

resource "aws_volume_attachment" "data" {
  device_name = var.data_volume_device_name
  volume_id   = aws_ebs_volume.data.id
  instance_id = aws_instance.this.id
}
