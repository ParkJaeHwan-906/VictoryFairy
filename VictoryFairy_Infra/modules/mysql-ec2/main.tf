# mysql-ec2 모듈: 단일 고정 EC2에 MySQL + 서비스 Redis 컨테이너 자체 호스팅
# (RDS 미사용, 비용 사유 — ARCHITECTURE.md §3 데이터 티어)
#
# 설계 요지:
#   - 앱과 격리된 비 EKS EC2 1대(운영 AZ 2a 프라이빗 서브넷). 오토스케일 없음(수직 승급).
#   - 데이터는 별도 EBS(gp3)에 영속(prevent_destroy). 인스턴스와 라이프사이클 분리.
#   - 접근은 SSM 뿐(22/3306 개발자 인입 없음, SKILL §6). 클러스터 내부만 3306/6379 허용.
#   - 백업: user_data 의 mysqldump→S3 크론 + 병행 EBS DLM 스냅샷(SKILL §5).
#
# ⚠ 커플링/주의: 6379(서비스 Redis)는 user·quiz 노드만 접근하고 batch 는 접근 못 한다.
#   현재 eks 노드 SG가 공용 하나라 물리적으로는 같은 SG가 3306/6379 양쪽에 들어갈 수 있으나,
#   "배치는 6379 불가"라는 의도를 변수(mysql_ingress_sg_ids vs redis_ingress_sg_ids)로 분리해
#   드러낸다. 노드그룹이 전용 SG로 분리되면 redis 목록에서 batch SG를 빼면 실효를 갖는다.

# ---------------------------------------------------------------------------
# Data sources
# ---------------------------------------------------------------------------
data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

# 인스턴스가 놓일 서브넷의 AZ — EBS 볼륨은 동일 AZ에 생성해야 부착 가능.
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
# IAM: 인스턴스 역할 (SSM 접근 + 백업 S3 PutObject + SSM 파라미터 조회) — 최소 권한
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

# SSM Session Manager 접근(개발자 포트포워딩·세션). 인바운드 포트 개방 없이 접근하는 유일 경로.
resource "aws_iam_role_policy_attachment" "ssm_core" {
  role       = aws_iam_role.this.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# 백업 업로드 + 비밀번호 조회 인라인 정책 — action/resource ARN 을 명시, "*" 지양(SKILL §7).
data "aws_iam_policy_document" "instance" {
  # mysqldump 백업을 정해진 버킷/prefix 하위로만 업로드.
  statement {
    sid       = "BackupPutObject"
    actions   = ["s3:PutObject"]
    resources = [local.backup_object_arn]
  }

  # MySQL root 비밀번호(SecureString)를 지정된 파라미터에서만 조회.
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
# 보안그룹 — 인입은 3306(user·quiz·batch), 6379(user·quiz)만. SSH(22) 인입 없음.
# ---------------------------------------------------------------------------
resource "aws_security_group" "this" {
  name        = "${local.name}-sg"
  description = "MySQL/Redis host. Ingress only from EKS node SGs; access via SSM only."
  vpc_id      = var.vpc_id

  tags = merge(var.tags, {
    Name = "${local.name}-sg"
  })

  lifecycle {
    create_before_destroy = true
  }
}

# 3306 ← MySQL 소스 SG(user·quiz·batch). map(정적 키 / apply-time 값)으로 for_each.
# (SG ID가 apply 시점 unknown이라 toset(list)로는 for_each 키를 못 만든다 → map 사용)
resource "aws_vpc_security_group_ingress_rule" "mysql" {
  for_each = var.mysql_ingress_sg_ids

  security_group_id            = aws_security_group.this.id
  referenced_security_group_id = each.value
  from_port                    = 3306
  to_port                      = 3306
  ip_protocol                  = "tcp"
  description                  = "MySQL 3306 from EKS node SG (user/quiz/batch)"

  tags = merge(var.tags, {
    Name = "${local.name}-mysql-${each.key}"
  })
}

# 6379 ← 서비스 Redis 소스 SG(user·quiz'만'). batch 는 여기 포함하지 않는다(ARCHITECTURE §3/§4).
# map(정적 키 / apply-time 값)으로 for_each — unknown SG ID를 키로 못 쓰기 때문.
resource "aws_vpc_security_group_ingress_rule" "redis" {
  for_each = var.redis_ingress_sg_ids

  security_group_id            = aws_security_group.this.id
  referenced_security_group_id = each.value
  from_port                    = 6379
  to_port                      = 6379
  ip_protocol                  = "tcp"
  description                  = "Redis 6379 from EKS node SG (user/quiz only, NOT batch)"

  tags = merge(var.tags, {
    Name = "${local.name}-redis-${each.key}"
  })
}

# 아웃바운드 전체 허용 — 패키지 설치·백업 S3 업로드·SSM 엔드포인트 통신 등.
resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.this.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
  description       = "Allow all outbound (package/backup/SSM)"

  tags = merge(var.tags, {
    Name = "${local.name}-egress"
  })
}

# ---------------------------------------------------------------------------
# EC2 인스턴스 — 프라이빗 서브넷, IMDSv2 강제, SSM 전용 접근.
# ---------------------------------------------------------------------------
resource "aws_instance" "this" {
  ami                    = data.aws_ami.al2023.id
  instance_type          = var.instance_type
  subnet_id              = var.subnet_id
  vpc_security_group_ids = [aws_security_group.this.id]
  iam_instance_profile   = aws_iam_instance_profile.this.name

  user_data = templatefile("${path.module}/templates/user_data.sh.tftpl", {
    data_volume_device_name                = var.data_volume_device_name
    backup_s3_bucket                       = var.backup_s3_bucket
    backup_s3_prefix                       = var.backup_s3_prefix
    aws_region                             = data.aws_region.current.name
    mysql_root_password_ssm_parameter_name = var.mysql_root_password_ssm_parameter_name
    innodb_buffer_pool_size                = var.innodb_buffer_pool_size
    redis_maxmemory                        = var.redis_maxmemory
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
}

# ---------------------------------------------------------------------------
# 데이터 EBS (gp3) — 인스턴스와 라이프사이클 분리, prevent_destroy 로 실수 삭제 방지.
# ⚠ 이 볼륨은 prevent_destroy 라 destroy/replace 가 계획되면 apply 가 '실패'한다.
#   의도적 삭제 시엔 먼저 이 lifecycle 블록을 제거해야 한다(스냅샷 확인 후).
# ---------------------------------------------------------------------------
resource "aws_ebs_volume" "data" {
  availability_zone = data.aws_subnet.this.availability_zone
  size              = var.data_volume_size_gb
  type              = "gp3"
  encrypted         = true

  tags = merge(
    var.tags,
    local.backup_selector_tag, # DLM 이 이 태그로 대상 볼륨을 선택
    { Name = "${local.name}-data" }
  )

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_volume_attachment" "data" {
  device_name = var.data_volume_device_name
  volume_id   = aws_ebs_volume.data.id
  instance_id = aws_instance.this.id

  # 인스턴스 교체 시 볼륨을 강제 분리하지 않는다(데이터 보호). 필요 시 수동 처리.
  stop_instance_before_detaching = true
}

# ---------------------------------------------------------------------------
# 백업 병행책: DLM 라이프사이클 정책으로 데이터 EBS 일 단위 스냅샷 자동화(SKILL §5).
# enable_dlm_snapshot 로 토글. (조건부 생성이므로 count 사용 — SKILL §8)
#
# 미결정 처리: ARCHITECTURE 상 'DLM 스냅샷용 공유 IAM 정책'은 security 모듈 소관으로
#   언급되나 security 모듈이 아직 미구현이다. 모듈 자립성을 위해 DLM 서비스 역할을
#   여기서 생성한다. 향후 security 모듈로 옮기면 execution_role_arn 을 입력 변수로
#   주입받도록 리팩터링(그때 moved/변수화)할 것.
# ---------------------------------------------------------------------------
data "aws_iam_policy_document" "dlm_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["dlm.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "dlm" {
  count = var.enable_dlm_snapshot ? 1 : 0

  name               = "${local.name}-dlm-role"
  assume_role_policy = data.aws_iam_policy_document.dlm_assume.json

  tags = merge(var.tags, {
    Name = "${local.name}-dlm-role"
  })
}

resource "aws_iam_role_policy_attachment" "dlm" {
  count = var.enable_dlm_snapshot ? 1 : 0

  role       = aws_iam_role.dlm[0].name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSDataLifecycleManagerServiceRole"
}

resource "aws_dlm_lifecycle_policy" "data" {
  count = var.enable_dlm_snapshot ? 1 : 0

  description        = "${local.name} MySQL data EBS daily snapshots"
  execution_role_arn = aws_iam_role.dlm[0].arn
  state              = "ENABLED"

  policy_details {
    resource_types = ["VOLUME"]
    # 데이터 볼륨에 붙인 selector 태그로 대상 지정(위 aws_ebs_volume.data 태그와 일치).
    target_tags = local.backup_selector_tag

    schedule {
      name = "daily"

      create_rule {
        interval      = 24
        interval_unit = "HOURS"
        times         = [var.dlm_snapshot_time_utc]
      }

      retain_rule {
        count = var.dlm_snapshot_retain_count
      }

      copy_tags = true

      tags_to_add = merge(var.tags, {
        Name = "${local.name}-snapshot"
      })
    }
  }

  tags = merge(var.tags, {
    Name = "${local.name}-dlm"
  })
}
