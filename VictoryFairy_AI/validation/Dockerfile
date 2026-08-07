# validation 모듈 — 검열 API (경량: FastAPI 만)
# 빌드 컨텍스트는 레포 루트: docker build -f validation/Dockerfile .
FROM python:3.11-slim

WORKDIR /app
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONPATH=/app

# 의존성 먼저 설치(레이어 캐시)
COPY validation/requirements.txt ./validation/requirements.txt
RUN pip install --no-cache-dir -r validation/requirements.txt

# 앱 코드
COPY validation/ ./validation/

EXPOSE 8000
CMD ["uvicorn", "validation.main:app", "--host", "0.0.0.0", "--port", "8000"]
