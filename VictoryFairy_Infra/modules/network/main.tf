# network 모듈: VPC + 다중 AZ 퍼블릭/프라이빗 서브넷 + NAT + 라우팅
#
# TODO: 아래 리소스를 구현합니다.
#   - aws_vpc
#   - aws_subnet (public/private, for_each 로 AZ별 생성)
#   - aws_internet_gateway
#   - aws_nat_gateway (+ EIP)  # 프라이빗 서브넷 아웃바운드
#   - aws_route_table / associations
#
# EKS 요구사항 태그:
#   - 퍼블릭 서브넷:  "kubernetes.io/role/elb"          = "1"
#   - 프라이빗 서브넷: "kubernetes.io/role/internal-elb" = "1"
#   두 서브넷 모두:     "kubernetes.io/cluster/<name>"   = "shared"
