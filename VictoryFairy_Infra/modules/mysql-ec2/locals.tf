locals {
  # 모든 리소스 이름·태그의 접두사. (이 모듈은 cluster_name을 받지 않으므로 environment 기반)
  name = "victoryfairy-mysql-${var.environment}"

  # DLM 이 대상 EBS를 선택하는 태그. 데이터 볼륨에 이 태그를 붙이고 정책이 이를 매칭한다.
  backup_selector_tag = {
    "victoryfairy:backup" = "mysql-data-${var.environment}"
  }

  # 백업 버킷/prefix 하위 객체 ARN (s3:PutObject 를 이 범위로만 제한).
  backup_object_arn = "arn:aws:s3:::${var.backup_s3_bucket}/${var.backup_s3_prefix}*"
}
