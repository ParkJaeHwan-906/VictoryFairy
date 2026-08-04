locals {
  # EKS 클러스터 이름의 단일 출처(single source of truth).
  # network 모듈(서브넷의 kubernetes.io/cluster/<name>=shared 태그)과 eks 모듈이
  # 반드시 같은 값을 써야 하므로 여기서 한 번만 정의해 양쪽에 전달한다.
  cluster_name = "victoryfairy-${var.environment}"

  # 수집기(py-collector) 스택이 소유한 리소스의 ARN.
  # 그 스택은 VictoryFairy_AI/py-collector/deploy/lambda/terraform 에 있고 state 가
  # 달라 모듈 출력으로 받을 수 없다 — 이름 규약(그쪽 var.name = "kbo-collector")대로
  # 조립한다. IAM 정책은 대상이 아직 없어도 만들어지므로 스택 생성 순서에 묶이지 않는다.
  # 소비처: modules/security → .github/workflows/deploy-collector.yml
  collector_account_prefix = "${var.aws_region}:${data.aws_caller_identity.current.account_id}"

  collector_ecr_repository_arn = "arn:aws:ecr:${local.collector_account_prefix}:repository/kbo-collector"

  # DB 잡 함수는 그쪽 스택의 db_subnet_ids 가 비면 생성되지 않는다. 존재하지 않는 ARN 을
  # 정책에 넣어도 무해하므로(권한은 리소스가 생길 때 유효해진다) 조건 없이 둘 다 넣는다.
  collector_function_arns = [
    "arn:aws:lambda:${local.collector_account_prefix}:function:kbo-collector",
    "arn:aws:lambda:${local.collector_account_prefix}:function:kbo-collector-db",
  ]
}
