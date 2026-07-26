# 정제 파이프라인 Lambda 이미지 — 패턴 검열 · Bedrock 2차 검열
#
# 두 함수가 **이 이미지 하나**를 공유하고 핸들러로만 갈린다:
#   pipeline.lambda_pattern.handler   S3 이벤트 1건당 게시글 1건
#   pipeline.lambda_bedrock.handler   SQS 배치당 모델 호출 1회
# 어느 핸들러로 뜰지는 Terraform 의 image_config.command 가 정한다(아래 CMD 는 기본값).
#
# ⚠️ **Lambda 베이스 이미지를 쓴다.** 일반 python 이미지 + `CMD python -m ...` 는
# Lambda 컨테이너로 동작하지 않는다 — Runtime Interface Client 가 없어 Runtime API
# 루프를 돌지 못하고 함수가 뜨자마자 죽는다. 베이스 이미지가 그 클라이언트를 제공한다.
#
# ⚠️ 그 대가로 **`run_validation` · `run_bedrock` · `run_backfill` 의 main() 은 이
# 이미지로 돌지 않는다.** 진입점이 Lambda 핸들러로 고정되기 때문이다. 세 러너는 코드로
# 보존되며 **로컬 `.venv` 에서 실행**한다(수동 재처리·디버깅·백필). 백필은 어차피
# Lambda 15분 상한에 맞지 않아 실행 방식이 별도 미결정 항목으로 열려 있다.
#
# ⚠️ analysis 계열(torch·kiwipiepy·transformers·NER 모델)은 **의도적으로 없다**
# (PIPE-S3IO-40). 정제가 쓰지 않는데 이미지를 1.63GB → 274MB 차이로 부풀린다.
# run_analysis·run_aggregate 는 analysis/Dockerfile 이미지나 로컬 .venv 를 쓴다.
#
# ⚠️ bedrock 은 전용 이미지가 없다 — 여기에 함께 실린다. COPY 를 빠뜨리면 컨테이너
# 안에서만 ImportError 로 죽는다(로컬은 소스 트리가 보여 재현되지 않아 놓치기 쉽다).
#
# ⚠️ **아키텍처를 명시적으로 고정한다.** Lambda 는 함수의 `architectures` 와 이미지
# 아키텍처가 다르면 **생성 자체가 실패**한다. 맥(Apple Silicon)에서 빌드하면 arm64,
# CI 러너가 x86 이면 amd64 가 나와 **빌드 장소에 따라 결과가 갈린다.**
# arm64(Graviton)를 택했다 — x86 대비 약 20% 저렴하고, 이 프로젝트의 주 빌드 환경이
# 맥이라 크로스 빌드 없이 나온다. Terraform 의 `architectures = ["arm64"]` 와 반드시
# 같아야 한다(한쪽만 바꾸면 apply 가 깨진다).
#
# 다른 아키텍처에서 빌드할 때는 크로스 빌드가 필요하다:
#     docker buildx build --platform linux/arm64 -f pipeline/Dockerfile .
#
# 이미지는 약 1.09GB 다. 우리 코드·의존성은 39MB 뿐이고 나머지는 Lambda 베이스
# 이미지다(Amazon Linux + Runtime Interface Client). EKS Spot 과 달리 Lambda 는
# 이미지를 캐시하고 청크 단위로 지연 로딩하므로 이 크기가 콜드 스타트를 크게 좌우하지
# 않는다 — 노드가 뜰 때마다 pull 하던 배치 이미지와는 상황이 다르다.
#
# ⚠️ **빌드 명령에 `--provenance=false --sbom=false` 가 반드시 들어가야 한다.**
# buildx 는 기본으로 provenance attestation 을 붙이는데, 그러면 매니페스트가
# **OCI image index(멀티아치 목록)** 형태가 된다 — 안에 든 이미지가 arm64 하나뿐이어도
# 그렇다. **Lambda 는 단일 아키텍처 매니페스트만 받으므로 함수 생성이 거부된다.**
# `docker build` 출력만 봐서는 드러나지 않고 ECR 매니페스트를 열어야 보인다(실제로 겪었다).
#
#     docker build --provenance=false --sbom=false --platform linux/arm64 \
#       -f pipeline/Dockerfile -t <ECR_URI>:<커밋SHA> .
#
# push 후 확인: mediaType 이 `application/vnd.oci.image.manifest.v1+json` 이어야 한다.
# `...image.index.v1+json` 이면 위 플래그가 빠진 것이다.
#
# 빌드 컨텍스트는 레포 루트.
FROM --platform=linux/arm64 public.ecr.aws/lambda/python:3.11

# ${LAMBDA_TASK_ROOT} = /var/task. 베이스 이미지가 이 경로를 sys.path 에 넣어 두므로
# 별도 PYTHONPATH 설정이 필요 없다.
ENV PYTHONDONTWRITEBYTECODE=1

# 의존성 — 전부 pure-python 이라 별도 인덱스·컴파일 단계가 없다.
# (베이스 이미지에도 boto3 가 있지만 버전을 고정하려고 직접 설치한다. Converse API 는
#  boto3 1.34.116+ 가 필요하다.)
COPY pipeline/requirements.txt ${LAMBDA_TASK_ROOT}/pipeline/requirements.txt
RUN pip install --no-cache-dir -r ${LAMBDA_TASK_ROOT}/pipeline/requirements.txt

# 핸들러가 import 하는 모듈 + 핸들러 코드.
# validation/ 에는 검열 사전(core/data/*.json)이 함께 들어간다 — 빠지면 기동 시 죽는다.
COPY validation/ ${LAMBDA_TASK_ROOT}/validation/
COPY bedrock/ ${LAMBDA_TASK_ROOT}/bedrock/
COPY pipeline/ ${LAMBDA_TASK_ROOT}/pipeline/

# 기본 핸들러. 실제 배포에서는 Terraform 이 함수별로 덮어쓴다.
CMD ["pipeline.lambda_pattern.handler"]
