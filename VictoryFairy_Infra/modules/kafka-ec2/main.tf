# kafka-ec2 모듈: chat 모듈이 쓰는 Kafka 를 전용 EC2(비 EKS)에 KRaft 단일 브로커로 자체 호스팅.
# (MSK 미사용 — 비용 사유. mysql-ec2 와 같은 패턴: AL2023 + docker 컨테이너 + 별도 EBS + SSM 전용 접근)
#
# ⚠ 단일 브로커(RF 1)라 acks=all 도 사실상 1이다. "202 를 돌려준 메시지는 안 사라진다"는
#   이 데이터 EBS 가 살아 있을 때까지만 참이다(요구사항 game-chat.md 제약 12).
# ⚠ 커플링: 앱은 KAFKA_BOOTSTRAP_SERVERS=<private_ip>:9092 로 붙는다(출력 bootstrap_servers).
#   advertised listener 가 이 인스턴스의 프라이빗 IP 라, 인스턴스가 재생성되면 IP 가 바뀌고
#   k8s 쪽 KAFKA_BOOTSTRAP_SERVERS 값도 함께 갱신해야 한다.

# 인스턴스가 놓일 서브넷의 AZ — EBS 볼륨은 동일 AZ에 생성해야 부착 가능.
data "aws_subnet" "this" {
  id = var.subnet_id
}

# 최신 Amazon Linux 2023 AMI (x86_64). 아래 ignore_changes[ami] 로 드리프트는 무시한다.
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
# IAM: 인스턴스 역할 — SSM Session Manager 만. 브로커는 AWS API 를 부르지 않으므로
#   인라인 정책이 없다(최소 권한, SKILL §7).
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

resource "aws_iam_role_policy_attachment" "ssm_core" {
  role       = aws_iam_role.this.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "this" {
  name = "${local.name}-profile"
  role = aws_iam_role.this.name

  tags = merge(var.tags, {
    Name = "${local.name}-profile"
  })
}

# ---------------------------------------------------------------------------
# 보안그룹 — 인입은 9092 ← EKS 노드 SG 만. SSH(22)·컨트롤러(9093) 인입 없음
#   (컨트롤러 리스너는 localhost 에만 바인드한다 — user_data 참조).
# ---------------------------------------------------------------------------
resource "aws_security_group" "this" {
  name        = "${local.name}-sg"
  description = "Kafka broker host. Ingress 9092 only from EKS node SGs; access via SSM only."
  vpc_id      = var.vpc_id

  tags = merge(var.tags, {
    Name = "${local.name}-sg"
  })

  lifecycle {
    create_before_destroy = true
  }
}

# map(정적 키 / apply-time 값)으로 for_each — SG ID 가 apply 시점 unknown 이라
# toset(list) 로는 for_each 키를 못 만든다(mysql-ec2 와 같은 이유).
resource "aws_vpc_security_group_ingress_rule" "kafka" {
  for_each = var.kafka_ingress_sg_ids

  security_group_id            = aws_security_group.this.id
  referenced_security_group_id = each.value
  from_port                    = local.client_port
  to_port                      = local.client_port
  ip_protocol                  = "tcp"
  description                  = "Kafka ${local.client_port} from EKS node SG"

  tags = merge(var.tags, {
    Name = "${local.name}-kafka-${each.key}"
  })
}

# 아웃바운드 전체 허용 — 패키지·이미지 pull(NAT 경유)·SSM 엔드포인트 통신 (mysql-ec2 관례).
resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.this.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
  description       = "Allow all outbound (package/image pull/SSM)"

  tags = merge(var.tags, {
    Name = "${local.name}-egress"
  })
}

# ---------------------------------------------------------------------------
# EC2 인스턴스 — 프라이빗 서브넷, 공인 IP 없음, IMDSv2 강제.
# ---------------------------------------------------------------------------
resource "aws_instance" "this" {
  ami                         = data.aws_ami.al2023.id
  instance_type               = var.instance_type
  subnet_id                   = var.subnet_id
  vpc_security_group_ids      = [aws_security_group.this.id]
  iam_instance_profile        = aws_iam_instance_profile.this.name
  associate_public_ip_address = false

  user_data = templatefile("${path.module}/templates/user_data.sh.tftpl", {
    client_port             = local.client_port
    data_volume_device_name = var.data_volume_device_name
    kafka_container_memory  = var.kafka_container_memory
    kafka_heap_size         = var.kafka_heap_size
    kafka_image             = var.kafka_image
    log_retention_hours     = var.log_retention_hours
    swap_size_mb            = var.swap_size_mb
    topics                  = var.topics
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
    http_tokens   = "required" # IMDSv2 강제 (user_data 의 프라이빗 IP 조회도 토큰 방식)
    http_endpoint = "enabled"
  }

  tags = merge(var.tags, {
    Name = local.name
  })

  lifecycle {
    # mysql-ec2 와 같은 이유: AMI 부동으로 브로커가 재생성되면 프라이빗 IP(=advertised
    # listener)가 바뀌고, user_data 변경은 cloud-init 재실행 없이 stop→start 만 일으킨다.
    # → 실행 중 브로커의 설정 변경은 SSM 으로 패치하고, 템플릿은 '다음 생성 시의 정답'으로 관리.
    ignore_changes = [ami, user_data]
  }
}

# ---------------------------------------------------------------------------
# 데이터 EBS (gp3) — Kafka 로그 디렉터리. 인스턴스와 라이프사이클 분리 + prevent_destroy.
# ⚠ destroy/replace 가 계획되면 apply 가 '실패'한다. 의도적 삭제는 lifecycle 블록 제거 후.
# ---------------------------------------------------------------------------
resource "aws_ebs_volume" "data" {
  availability_zone = data.aws_subnet.this.availability_zone
  size              = var.data_volume_size_gb
  type              = "gp3"
  encrypted         = true

  tags = merge(var.tags, {
    Name = "${local.name}-data"
  })

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_volume_attachment" "data" {
  device_name = var.data_volume_device_name
  volume_id   = aws_ebs_volume.data.id
  instance_id = aws_instance.this.id

  # 분리 전에 인스턴스를 멈춰 브로커가 쓰는 중에 볼륨이 빠지지 않게 한다.
  stop_instance_before_detaching = true
}
