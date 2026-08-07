variable "aws_region" {
  description = "리소스를 생성할 AWS 리전"
  type        = string
  default     = "ap-northeast-2" # 서울
}

variable "environment" {
  description = "배포 환경"
  type        = string
  default     = "dev"
  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "environment는 dev 또는 prod 여야 합니다."
  }
}

variable "vpc_cidr" {
  description = "VPC CIDR 블록"
  type        = string
  default     = "10.0.0.0/16"
}

variable "azs" {
  description = "사용할 가용영역 (다중 AZ)"
  type        = list(string)
  default     = ["ap-northeast-2a", "ap-northeast-2c"]
}

variable "backup_s3_bucket" {
  description = "MySQL 일 단위 백업을 저장할 S3 버킷 이름"
  type        = string
}

variable "dev_db_allowed_cidrs" {
  description = "dev DB SSH·3306·6379 를 허용할 CIDR 목록(예: [\"1.2.3.4/32\", \"5.6.7.8/32\"]). 비우면([]) dev DB 미생성."
  type        = list(string)
  default     = []
}

variable "dev_db_use_eip" {
  description = "true 면 dev DB 에 Elastic IP(고정 퍼블릭 IP)를 할당·연결. 인스턴스 중지 중엔 미사용 EIP 요금 발생."
  type        = bool
  default     = false
}

variable "mysql_public_access_cidrs" {
  description = <<-EOT
    운영 MySQL EC2 에 개발자 PC 가 '직접' 접속(3306)하도록 허용할 CIDR 목록
    (예: ["1.2.3.4/32"]). 비우면([]) 퍼블릭 경로(보조 ENI·EIP·SG)를 만들지 않고
    종전처럼 SSM 포트포워딩만 남는다.
    ⚠ 운영 데이터 호스트를 인터넷에 노출하는 선택이다. /32 로 좁게 유지할 것.
  EOT
  type        = list(string)
  default     = []
}

variable "domain_name" {
  description = "서비스 루트 도메인. Route53 호스팅영역 + ACM 인증서 기준. (dns 모듈)"
  type        = string
  default     = "victoryfairy.com"
}

variable "fe_attach_apex_alias" {
  description = <<-EOT
    루트 도메인을 CloudFront 로 넘길지 여부. FE 정적 호스팅 전환의 실서비스 스위치다.

    false(기본) = CloudFront·S3 는 만들되 트래픽은 그대로 ALB 로 간다. 배포 도메인
    (terraform output fe_cloudfront_domain_name)으로 직접 접속해 검증하는 단계.
    true = 루트 도메인 A/AAAA 를 CloudFront 로 교체한다.

    ⚠ true 로 바꾸기 전에 앱 Ingress 전부에 external-dns controller: none 이 붙어 있어야 한다.
      순서를 뒤집으면 ExternalDNS 가 1분 내 레코드를 ALB 로 되돌려 계속 다툰다.
      절차는 docs/fe-cdn-migration.md §4.

    ⚠ 기본값이 true 인 이유: 2026-08-07 전환 완료 후 코드가 현실과 일치해야 한다. false 로
      되돌려 apply 하면 Terraform 이 apex 레코드를 '삭제'해 도메인이 어디도 가리키지 않는다.
      롤백은 이 값을 내리는 것이 아니라 레코드를 ALB 로 수동 UPSERT 하는 것이다(문서 §4 롤백).
      terraform.tfvars 는 gitignore 라 공유되지 않으므로 이 값은 반드시 기본값으로 들고 있어야 한다.
  EOT
  type        = bool
  default     = true
}

variable "crawl_bucket_name" {
  description = "크롤 원본과 정제 산출물이 함께 있는 S3 버킷. ⚠ Terraform 관리 밖이며 refine-pipeline 은 알림 설정만 붙인다"
  type        = string
  default     = "victoryfairy-crawl-dev"
}

variable "bedrock_batch_post_size" {
  description = "Bedrock Lambda 가 한 번의 모델 호출에 묶는 게시글 수(= SQS batch_size). 처리량 부족은 이 값으로 푼다 — 예약 동시성은 올리지 않는다"
  type        = number
  default     = 7

  # ⚠ 2026-07-29 실측으로 15 → 7 로 내렸다. 아래 "추정치" 기반 계산이 틀렸다.
  #
  #   CloudWatch 로그(/aws/lambda/victoryfairy-dev-refine-bedrock)에 폴백 통과가
  #   **이미 매일 4~6건씩 쌓이고 있었다**(7일간 21건 / 하루 약 1,280 호출 = 0.43%).
  #   메시지가 계산을 그대로 검증해 준다:
  #     "판정을 얻지 못해 92개 단위를 폴백 통과 처리합니다
  #      (마지막 오류: 모델 응답이 JSON 이 아닙니다: Unterminated string ... char 5227)"
  #
  #   여기서 두 가지가 확정됐다.
  #   1) 2048 토큰 = 약 85 판정 단위 (응답이 일관되게 char 5,107~5,284 에서 잘린다).
  #      → 단위당 약 24토큰. 4096 의 수용량은 약 170단위.
  #   2) **게시글당 단위 수 추정이 틀렸다.** 실패한 배치가 batch_size=5 에서
  #      86~99단위였다 = 게시글당 17~20단위. 댓글 5개(6단위)로 잡았던 가정과 3배 차이다.
  #      커뮤니티 인기글이 댓글 상한 20개를 그대로 채운다.
  #
  #   따라서 15 는 평균 기준으로도 위험하다(15 × 18 ≈ 270단위 > 170).
  #   7 은 **최악까지 보장된다**: 7 × 21(게시글당 최대) = 147단위 ≈ 3,530토큰 < 4096.
  #   8 이 이론상 한계지만(168단위 ≈ 4,030토큰) 여유가 없어 7 로 둔다.
  #
  #   비용은 여전히 개선이다 — $44.8/N 곡선에서 N=5 $8.97 → N=7 $6.4.
  #   처리량도 1.4배. 무엇보다 매일 5건씩 나던 미검열 통과가 구조적으로 사라진다.
  #
  #   ⚠ 더 올리려면 **먼저 실측할 것.** lambda_bedrock.handler 의 judge_batch() 직후에
  #     judgement.usage.output_tokens 와 len(items) 를 찍으면 성공 배치의 분포를 알 수
  #     있다(지금은 실패 배치만 로그에 남아 표본이 편향돼 있다).
  #
  # 예약 동시성이 1이라 처리량을 늘리는 합법적 레버는 이 값뿐이다.
  # 비용은 부수 효과로 함께 내려간다 — 비용이 사실상 전부 시스템 프롬프트(2,470토큰)라
  # 하루 소비액이 대략 $44.8/N 로 움직인다(N=5 → $8.97, N=7 → $6.4, 상한 $30).
  #
  # ⚠ **이 값은 bedrock_max_output_tokens 와 짝이다 — 혼자 올리지 말 것.**
  #   pipeline/lambda_bedrock.py 의 handler 는 배치를 쪼개지 않고 **전건을 한 번의
  #   judge_batch() 로 부르고**, 게시글 1건은 `1 + 댓글 수` 개의 판정 단위로 펼쳐진다.
  #   댓글은 크롤러의 COLLECTOR_TOP_COMMENTS=20 에서 잘리므로 게시글당 최대 21단위다.
  #
  #   실측: 2048 토큰 = 약 85단위 → 단위당 약 24토큰. 4096 의 수용량은 약 170단위.
  #     N=7 최악(전건 댓글 20개) → 147단위 ≈ 3,530토큰  < 4096  → 보장됨
  #
  #   초과하면 응답이 잘려 "항목 수 불일치"가 되고, 2회 재시도 후 **배치 전건이
  #   폴백 통과 처리된다**(BRK-LLM-15). 실패로 떨어지는 게 아니라 미검열 콘텐츠가
  #   조용히 통과한다. 호출 비용은 재시도까지 3번 다 나간다.
  #
  #   댓글 상한(COLLECTOR_TOP_COMMENTS=20)이 게시글당 단위를 21 로 묶어 주므로,
  #   N × 21 ≤ 수용량 을 만족하면 최악 케이스까지 보장된다. N=7 이 그 조건을 만족한다.
  #   감시는 계속할 것 — CloudWatch 에서 "폴백 통과" / "스키마 불일치" 로그.
}

variable "bedrock_max_output_tokens" {
  description = "모델 응답의 출력 토큰 상한(Lambda 의 BEDROCK_MAX_TOKENS). 배치 크기와 짝으로 움직인다"
  type        = number
  default     = 4096

  # 2048 → 4096 (2026-07-29). 배치 상향과 짝으로 올렸다.
  # 출력 토큰은 **실제 생성분만 과금**되므로 상한 인상 자체의 비용은 사실상 없다.
  # 4096 이 이 모델의 천장이다 — modules/refine-pipeline 의 validation 참고
  # (Converse API 를 베타 헤더 없이 부르므로 8192 는 쓸 수 없다).
}

variable "refine_image_tag" {
  description = "정제 러너 Lambda 컨테이너 이미지 태그. VictoryFairy_AI 의 커밋 SHA 를 쓴다(불변 태그)"
  type        = string

  # VictoryFairy_AI dev_ai 계열 커밋 aea6fca5 로 빌드해 push 한 이미지.
  # 리포지토리가 IMMUTABLE 이라 같은 태그 재push 가 막히므로, 이 값이 곧 배포된 코드다.
  #
  # ⚠ 이미지를 바꾸려면 **AI 저장소에서 빌드·push 한 뒤 이 값을 갱신**한다. 태그가 ECR 에
  #   없으면 apply 가 Lambda 생성에서 실패한다(plan 은 통과하므로 plan 만으로는 못 잡는다).
  #   아키텍처도 arm64 여야 한다 — 다르면 함수 생성 자체가 실패한다(modules/refine-pipeline 주석).
  default = "aea6fca5"
}
