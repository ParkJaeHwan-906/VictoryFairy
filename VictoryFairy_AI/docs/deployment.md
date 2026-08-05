# 배포 (모듈별 Docker 컨테이너)

각 모듈을 독립 컨테이너로 빌드·기동한다. 빌드 컨텍스트는 **항상 레포 루트**다
(pipeline이 validation·analysis 패키지를 import 하므로).

## 구성

| 모듈 | Dockerfile | 포트 | 특징 |
|---|---|---|---|
| validation | `validation/Dockerfile` | 8000 | 경량(FastAPI만) |
| analysis | `analysis/Dockerfile` | 8001 | kiwipiepy + transformers + **torch(CPU)**, NER 모델 빌드 시 캐시 |
| pipeline | `pipeline/Dockerfile` | — | 배치 러너(서버 아님). CMD는 검열 러너(`run_validation`) 단독 — **S3 게시글 in/out**, AWS env 주입(볼륨 없음) |

- 모듈별 의존성은 각 `*/requirements.txt`로 분리(루트 `requirements.txt`는 로컬 개발용 통합본).
- torch는 CPU 빌드(`--index-url .../whl/cpu`)로 설치해 이미지 비대화를 막는다.
- NER 모델(`Leo97/KoELECTRA-small-v3-modu-ner`)은 빌드 시 미리 캐시 → 첫 기동 지연·런타임 네트워크 의존 제거.

## 실행 (docker compose)

```bash
# API 서버 2종 기동 (validation:8000, analysis:8001)
docker compose up --build validation analysis

# 검열 러너 1회 실행 (S3 게시글 in/out). .env 에 S3_BUCKET·AWS_* 설정 필요.
docker compose --profile batch run --rm pipeline
```

## 개별 빌드 (compose 없이)

```bash
docker build -f validation/Dockerfile -t victoryfairy-validation .
docker build -f analysis/Dockerfile   -t victoryfairy-analysis .
docker build -f pipeline/Dockerfile   -t victoryfairy-pipeline .

docker run -p 8000:8000 victoryfairy-validation
docker run -p 8001:8001 victoryfairy-analysis
docker run --env-file .env victoryfairy-pipeline   # 검열 러너: S3_BUCKET·AWS_* 필요, data/ 볼륨 불필요
```

## 주의

- **analysis/pipeline 빌드는 무겁다**(torch + 모델 다운로드로 수 분·수백 MB~GB). validation은 가볍고 빠르다.
- 검열 러너(`run_validation`)는 이제 S3 in/out이라 `data/` 볼륨이 필요 없다(대신 `S3_BUCKET`·`AWS_*` env 주입). `data/`는 여전히 로컬 전용이라 이미지에 넣지 않으며(`.dockerignore`에서 제외), 배선에서 빠진 `run_analysis`/`run_aggregate`를 개별 실행할 때만 볼륨 마운트가 필요하다.
- 모듈 사전(`*/core/data/*.json`)은 코드이므로 이미지에 포함된다(`.dockerignore`가 최상위 `data/`만 제외).
- 검증됨: validation 이미지 빌드·기동·엔드포인트 응답 확인 완료.
