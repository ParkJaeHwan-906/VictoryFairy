# mysql-ec2 모듈: EC2에 MySQL 컨테이너 자체 호스팅 (RDS 미사용, 비용 사유)
#
# TODO: 아래를 구현합니다.
#   - aws_instance
#       * subnet_id = var.subnet_id (프라이빗)
#       * IAM instance profile: AmazonSSMManagedInstanceCore (SSM 접근)
#         + backup_s3_bucket 에 대한 s3:PutObject 최소 권한
#       * user_data: docker + MySQL 컨테이너, 데이터 디렉토리를 EBS 마운트 지점에 배치
#   - aws_ebs_volume (gp3, var.data_volume_size_gb) + aws_volume_attachment
#       * lifecycle { prevent_destroy = true }  # 데이터 볼륨 실수 삭제 방지
#   - aws_security_group
#       * ingress 3306 <- var.allowed_source_sg_ids (EKS 노드 SG) 만 허용
#       * SSH(22) 인입 규칙 없음 — 접근은 SSM 뿐 (SKILL §6)
#
# 백업 (SKILL §5): MySQL EBS 데이터는 하루 단위로 S3에 백업.
#   - user_data 또는 크론에 mysqldump -> gzip -> aws s3 cp s3://${var.backup_s3_bucket}/ (매일 1회)
#   - 병행: aws_dlm_lifecycle_policy 로 EBS 스냅샷 일 단위 자동화 권장
#   - 이중화(dump + 스냅샷)로 인스턴스/AZ 장애 시 데이터 유실 방지.
