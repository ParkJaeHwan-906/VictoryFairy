locals {
  # EKS 클러스터 이름의 단일 출처(single source of truth).
  # network 모듈(서브넷의 kubernetes.io/cluster/<name>=shared 태그)과 eks 모듈이
  # 반드시 같은 값을 써야 하므로 여기서 한 번만 정의해 양쪽에 전달한다.
  cluster_name = "victoryfairy-${var.environment}"

  # CloudFront 가 ALB 오리진을 부르는 이름의 단일 출처. 세 곳이 같은 값을 써야 한다:
  #   1) modules/dns  — 서울 인증서의 SAN (없으면 CloudFront→ALB TLS 가 이름 불일치로 거부된다)
  #   2) modules/cdn  — 오리진 도메인 + Host 헤더
  #   3) k8s/22-ingress.yaml — Ingress host (ExternalDNS 가 이 이름의 A 레코드를 만든다)
  # ⚠ 사용자 대면 이름이 아니다. 브라우저는 apex(victoryfairy.com)만 본다.
  api_origin_host = "origin.${var.domain_name}"

  # FE 정적 자산 경로는 ALB 가 아니라 CloudFront→S3 로 간다. 여기 없는 경로는 전부 S3 다.
  #   /api → user-app(인증·계정), /rt → quiz-app(채팅 SSE, realtime)
  # ⚠ BE 의 server.servlet.context-path 와 문자 그대로 일치해야 한다 — ALB 도 CloudFront 도
  #   경로를 rewrite 하지 않는다.
  api_path_patterns = ["/api/*", "/rt/*"]

  # 사용자 업로드 자산(프로필 이미지)의 단일 출처. 세 곳이 같은 값을 써야 한다:
  #   1) modules/asset    — 버킷 이름 + temp/ 만료 규칙 + 버킷 정책이 허용하는 접두사
  #   2) modules/cdn      — 경로 패턴 /user-profile-img/*, /temp/* (그 외는 종전대로 FE·ALB)
  #   3) modules/user-irsa — user-app 파드가 읽고 쓸 수 있는 접두사
  # ⚠ BE 가 만드는 S3 키와 문자 그대로 일치해야 한다. 어긋나면 업로드는 AccessDenied,
  #   조회는 CloudFront 가 FE 버킷으로 보내 404 가 된다.
  #
  # ⚠ 버킷 이름만 name_prefix(victoryfairy-dev) 규약에서 벗어난다 — BE·프론트와 합의된 이름이
  #   victoryfairy-asset 이라 그대로 못 박는다(modules/asset/variables.tf 참조).
  asset_bucket_name    = "victoryfairy-asset"
  asset_profile_prefix = "user-profile-img/"
  asset_temp_prefix    = "temp/"

  # 캐릭터 꾸미기 에셋. 위 둘과 같은 버킷이지만 성격이 다르다 — 저자가 파드가 아니라 사람이고
  # (scripts/upload-character-assets.sh), 앱은 이 접두사를 읽지도 쓰지도 않는다. BE 는 DB 에 담긴
  # EP 문자열을 그대로 내보낼 뿐이고 실제 파일은 브라우저가 CloudFront 에서 직접 받는다.
  # 그래서 두 곳만 같은 값을 쓰면 된다(user-irsa 는 대상이 아니다):
  #   1) modules/asset — 버킷 정책이 CloudFront 에게 읽기를 허용하는 접두사
  #   2) modules/cdn   — 경로 패턴 /characters/*, /items/*, /stores/*
  # ⚠ DB 시드(VictoryFairy_BE/infra/sql/character-asset-init.sql)에 박힌 EP 의 첫 세그먼트와
  #   문자 그대로 일치해야 한다. 어긋나면 CloudFront 가 FE 버킷으로 보내 404 가 된다.
  asset_static_prefixes = ["characters/", "items/", "stores/"]

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
