# analysis 모듈 — 형태소(Kiwi) + 개체명(NER) 추출 API
# 빌드 컨텍스트는 레포 루트: docker build -f analysis/Dockerfile .
FROM python:3.11-slim

WORKDIR /app
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONPATH=/app \
    HF_HOME=/app/.hf-cache

# torch 는 CPU 빌드로 설치(기본 CUDA 빌드는 수 GB) → 이후 나머지 의존성
COPY analysis/requirements.txt ./analysis/requirements.txt
RUN pip install --no-cache-dir torch==2.8.0 --index-url https://download.pytorch.org/whl/cpu \
 && pip install --no-cache-dir -r analysis/requirements.txt

# 앱 코드
COPY analysis/ ./analysis/

# NER 모델을 이미지에 미리 캐시(첫 기동 지연·런타임 네트워크 의존 제거)
RUN python -c "from transformers import pipeline; pipeline('ner', model='Leo97/KoELECTRA-small-v3-modu-ner', aggregation_strategy='simple')"

EXPOSE 8001
CMD ["uvicorn", "analysis.main:app", "--host", "0.0.0.0", "--port", "8001"]
