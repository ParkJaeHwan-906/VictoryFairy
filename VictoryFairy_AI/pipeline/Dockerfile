# pipeline 모듈 — 배치 러너(패턴 검열 → Bedrock 2차 검열 → 백필)
# validation·bedrock 서비스를 직접 import 하므로 두 패키지를 포함한다.
#
# ⚠️ analysis 는 **의도적으로 빠져 있다**(PIPE-S3IO-40).
# 현행 배치 흐름은 run_validation → run_bedrock 2단계뿐이고 analysis 는 배선에서
# 빠져 있다(PIPE-S3IO-28). 예전에는 이 이미지가 torch(CPU 빌드)와 KoELECTRA NER
# 모델까지 담아 1.63GB 였는데, **전부 쓰지 않는 무게였다.** Spot 노드는 뜰 때마다
# 이미지를 pull 하므로 그 무게가 기동 시간에 그대로 붙는다.
# → run_analysis·run_aggregate 는 이 이미지로 돌지 않는다. analysis/Dockerfile
#   이미지나 로컬 .venv 를 쓴다(파일·코드는 보존된다 — PIPE-S3IO-29).
#
# ⚠️ bedrock 은 전용 이미지가 없다 — 여기에 함께 실린다. COPY 를 빠뜨리면
# run_bedrock·run_backfill 이 컨테이너 안에서만 ImportError 로 죽는다(로컬에서는
# 소스 트리가 그대로 보여 재현되지 않으므로 놓치기 쉽다).
#
# CMD 는 패턴 러너지만 run_bedrock·run_backfill 도 같은 이미지로 돈다 —
# 오케스트레이터가 command 를 덮어써서 러너를 고른다.
# 빌드 컨텍스트는 레포 루트: docker build -f pipeline/Dockerfile .
FROM python:3.11-slim

WORKDIR /app
ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONPATH=/app

# 배치 의존성 — 전부 pure-python 이라 torch 같은 별도 인덱스·CPU 빌드가 필요 없다.
COPY pipeline/requirements.txt ./pipeline/requirements.txt
RUN pip install --no-cache-dir -r pipeline/requirements.txt

# 러너가 import 하는 모듈 + 러너 코드
COPY validation/ ./validation/
COPY bedrock/ ./bedrock/
COPY pipeline/ ./pipeline/

CMD ["python", "-m", "pipeline.run_validation"]
