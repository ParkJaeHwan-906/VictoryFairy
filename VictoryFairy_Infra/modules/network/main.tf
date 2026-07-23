# network 모듈: VPC + 2 AZ 퍼블릭/프라이빗 서브넷 + IGW + 단일 NAT + 라우팅
#
# 설계(ARCHITECTURE.md §1): 서브넷은 2 AZ(2a, 2c)에 선언하되 실제 노드·DB는 2a에
# 집중하고 2c는 예비. NAT는 비용상 2a 단일이며, 모든 프라이빗 서브넷이 이 NAT로
# 아웃바운드한다(2c 프라이빗은 크로스 AZ지만 예비라 트래픽 거의 없음).

# ---------------------------------------------------------------------------
# VPC
# ---------------------------------------------------------------------------
resource "aws_vpc" "this" {
  cidr_block = var.vpc_cidr

  # EKS는 DNS 호스트네임/지원이 켜져 있어야 한다(프라이빗 엔드포인트·노드 조인).
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = merge(var.tags, {
    Name = "${var.environment}-vpc"
  })
}

# ---------------------------------------------------------------------------
# 서브넷 (AZ별 퍼블릭 / 프라이빗) — for_each 로 AZ 이름 키 생성
# ---------------------------------------------------------------------------
resource "aws_subnet" "public" {
  for_each = local.subnets_by_az

  vpc_id            = aws_vpc.this.id
  cidr_block        = each.value.public_cidr
  availability_zone = each.value.az

  # 퍼블릭 서브넷(ALB·NAT 배치). 공용 IP 자동 할당.
  map_public_ip_on_launch = true

  tags = merge(var.tags, {
    Name = "${var.environment}-public-${each.value.az}"
    Tier = "public"
    # EKS: 외부(인터넷) 로드밸런서가 이 서브넷을 자동 발견하도록.
    "kubernetes.io/role/elb" = "1"
    # EKS/LB 컨트롤러 서브넷 자동 발견 태그. cluster_name 은 eks 모듈과 동일해야 함.
    "kubernetes.io/cluster/${var.cluster_name}" = "shared"
  })
}

resource "aws_subnet" "private" {
  for_each = local.subnets_by_az

  vpc_id            = aws_vpc.this.id
  cidr_block        = each.value.private_cidr
  availability_zone = each.value.az

  tags = merge(var.tags, {
    Name = "${var.environment}-private-${each.value.az}"
    Tier = "private"
    # EKS: 내부(internal) 로드밸런서가 이 서브넷을 자동 발견하도록.
    "kubernetes.io/role/internal-elb" = "1"
    # EKS/LB 컨트롤러 서브넷 자동 발견 태그. cluster_name 은 eks 모듈과 동일해야 함.
    "kubernetes.io/cluster/${var.cluster_name}" = "shared"
  })
}

# ---------------------------------------------------------------------------
# 인터넷 게이트웨이 (퍼블릭 아웃/인바운드)
# ---------------------------------------------------------------------------
resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id

  tags = merge(var.tags, {
    Name = "${var.environment}-igw"
  })
}

# ---------------------------------------------------------------------------
# NAT Gateway — 2a 단일 (프라이빗 서브넷 아웃바운드)
# ---------------------------------------------------------------------------
resource "aws_eip" "nat" {
  domain = "vpc"

  tags = merge(var.tags, {
    Name = "${var.environment}-nat-eip-${local.primary_az}"
  })

  # IGW가 있어야 NAT가 인터넷으로 나갈 수 있다.
  depends_on = [aws_internet_gateway.this]
}

resource "aws_nat_gateway" "this" {
  allocation_id = aws_eip.nat.id
  # 운영 AZ(2a)의 퍼블릭 서브넷에 단일 배치.
  subnet_id = aws_subnet.public[local.primary_az].id

  tags = merge(var.tags, {
    Name = "${var.environment}-nat-${local.primary_az}"
  })

  depends_on = [aws_internet_gateway.this]
}

# ---------------------------------------------------------------------------
# 라우팅 — 퍼블릭 → IGW / 프라이빗 → 단일 NAT
# ---------------------------------------------------------------------------
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this.id
  }

  tags = merge(var.tags, {
    Name = "${var.environment}-public-rt"
  })
}

# NAT가 단일이라 프라이빗 라우팅 테이블도 하나로 공유(모든 프라이빗 서브넷 → 2a NAT).
resource "aws_route_table" "private" {
  vpc_id = aws_vpc.this.id

  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.this.id
  }

  tags = merge(var.tags, {
    Name = "${var.environment}-private-rt"
  })
}

resource "aws_route_table_association" "public" {
  for_each = aws_subnet.public

  subnet_id      = each.value.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "private" {
  for_each = aws_subnet.private

  subnet_id      = each.value.id
  route_table_id = aws_route_table.private.id
}
