locals {
  # AZ 이름 → 해당 AZ의 퍼블릭/프라이빗 CIDR.
  # for_each 키를 리스트 인덱스가 아니라 안정적인 "AZ 이름"으로 잡아, CIDR 순서가
  # 바뀌어도 서브넷 리소스 주소가 흔들리지 않게 한다.
  subnets_by_az = {
    for idx, az in var.azs : az => {
      az           = az
      public_cidr  = var.public_subnet_cidrs[idx]
      private_cidr = var.private_subnet_cidrs[idx]
    }
  }

  # 운영 AZ = 목록의 첫 번째(= ap-northeast-2a).
  # NAT Gateway는 여기에만 두고(비용상 단일), 모든 프라이빗 서브넷의 아웃바운드가
  # 이 단일 NAT로 나간다. 2c는 EKS 컨트롤플레인 요건(2 AZ) 때문에 선언만 하는
  # 예비 AZ이며, 노드/DB 배치는 하지 않는다(배치는 eks / mysql-ec2 모듈 소관).
  primary_az = var.azs[0]
}
