# pipeline 모듈 — 배치 러너(패턴 검열 → Bedrock 2차 검열 → 백필)
# validation·bedrock 서비스를 직접 import 하므로 두 패키지를 모두 포함한다.
# analysis 는 현재 배선에서 빠져 있으나 파일·코드는 보존한다(PIPE-S3IO-29).
#
# ⚠️ bedrock 은 **전용 이미지가 없다** — 이 이미지에 함께 실린다. COPY 를 빠뜨리면
# run_bedrock·run_backfill 이 컨테이너 안에서 ImportError 로 죽는다(로컬에서는 소스
# 트리가 그대로 보여 재현되지 않으므로 놓치기 쉽다).
#
# CMD 는 패턴 러너지만 run_bedrock·run_backfill 도 같은 이미지로 돈다 — 오케스트레이터가
# command 를 덮어써서 러너를 고른다.
# 빌드 컨텍스트는 레포 루트: docker build -f pipeline/Dockerfile .
FROM python:3.11-slim

WORKDIR /app
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONPATH=/app \
    HF_HOME=/app/.hf-cache

# torch CPU 빌드 + 배치 의존성(boto3 포함 — S3 게시글별 .json 입출력, PIPE-S3IO-16)
COPY pipeline/requirements.txt ./pipeline/requirements.txt
RUN pip install --no-cache-dir torch==2.8.0 --index-url https://download.pytorch.org/whl/cpu \
 && pip install --no-cache-dir -r pipeline/requirements.txt

# 러너가 import 하는 모듈 + 러너 코드
COPY validation/ ./validation/
COPY bedrock/ ./bedrock/
COPY analysis/ ./analysis/
COPY pipeline/ ./pipeline/

# NER 모델 미리 캐시
RUN python -c "from transformers import pipeline; pipeline('ner', model='Leo97/KoELECTRA-small-v3-modu-ner', aggregation_strategy='simple')"

# 이번 이터레이션은 run_validation 단독 실행(S3 read/write, PIPE-S3IO-28).
# run_analysis·run_aggregate는 파일·코드 보존(PIPE-S3IO-29) — 이후 이터레이션에서 CMD 재배선 예정.
CMD ["python", "-m", "pipeline.run_validation"]
