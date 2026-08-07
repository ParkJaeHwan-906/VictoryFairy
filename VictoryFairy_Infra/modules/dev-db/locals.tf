locals {
  # 모든 리소스 이름·태그 접두사. 프로덕션(mysql-ec2: "victoryfairy-mysql-*")과
  # 이름 네임스페이스를 분리해 리소스 충돌을 방지한다(별도 모듈/별도 인스턴스).
  name = "victoryfairy-devdb-${var.environment}"

  # 백업 버킷 ARN 과 prefix 하위 객체 ARN.
  # dev DB 는 restore 전용이므로 s3:GetObject 는 이 prefix 하위로만 제한하고
  # s3:ListBucket 은 버킷 ARN + prefix 조건으로 최소화한다(쓰기 권한 없음).
  backup_bucket_arn = "arn:aws:s3:::${var.backup_s3_bucket}"
  backup_object_arn = "arn:aws:s3:::${var.backup_s3_bucket}/${var.backup_s3_prefix}*"
}
