# pipeline 모듈 — 배치 러너(검열 → 분석 → 집계)
# validation·analysis 서비스를 직접 import 하므로 두 패키지를 모두 포함한다.
# 빌드 컨텍스트는 레포 루트: docker build -f pipeline/Dockerfile .
FROM python:3.11-slim

WORKDIR /app
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONPATH=/app \
    HF_HOME=/app/.hf-cache

# torch CPU 빌드 + 배치 의존성
COPY pipeline/requirements.txt ./pipeline/requirements.txt
RUN pip install --no-cache-dir torch==2.8.0 --index-url https://download.pytorch.org/whl/cpu \
 && pip install --no-cache-dir -r pipeline/requirements.txt

# 러너가 import 하는 두 모듈 + 러너 코드
COPY validation/ ./validation/
COPY analysis/ ./analysis/
COPY pipeline/ ./pipeline/

# NER 모델 미리 캐시
RUN python -c "from transformers import pipeline; pipeline('ner', model='Leo97/KoELECTRA-small-v3-modu-ner', aggregation_strategy='simple')"

# data/ 는 볼륨으로 마운트한다(로컬 전용 파일 저장소). 기본은 3단계 순차 실행.
CMD ["sh", "-c", "python -m pipeline.run_validation && python -m pipeline.run_analysis && python -m pipeline.run_aggregate"]
