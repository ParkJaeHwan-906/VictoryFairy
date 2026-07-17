# kbo-collector (Python)

Java `collector`의 "크롤/API 호출 → 원본(raw) S3 적재" 기능을 파이썬으로 이관한 것입니다.
오케스트레이션에 비의존적인 순수 파이썬 코어(`kbo_collector`)와, 그 코어를 호출하는 얇은
서버리스 어댑터로 구성됩니다.

- **경기 데이터**: 네이버 스포츠 JSON(schedule / result / relay)을 byte-for-byte 적재
- **커뮤니티**: FMKorea·DCInside 글을 파싱해 `RawPost`로 적재(본문 원문 유지, 댓글 작성자 마스킹)
- 멱등 키 · S3 존재 체크포인트 · dead-letter · 저널로 중단/소스다운에도 견딤

## Setup
```bash
cd py-collector
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"
cp .env.example .env   # COLLECTOR_S3_BUCKET / COLLECTOR_PII_SALT 등 편집
```

## Test
```bash
pytest -q
```

## 직접 실행 (CLI)
```bash
# job ∈ {schedule, result, relay, community, all}
python -m kbo_collector.run community --date 2026-07-10
python -m kbo_collector.run all --date 2026-07-08
```
노트북으로 돌려보려면 `notebooks/run_crawler.ipynb`.

## 자동화 (서버리스, 호출한 만큼만 과금)
EventBridge 스케줄이 컨테이너-이미지 Lambda를 호출합니다:
- **community** — 10분마다(`rate(10 minutes)`) 새 글 증분 수집
- **daily** — 매일 schedule→result→relay

배포는 `deploy/lambda/`(핸들러 + Dockerfile + Terraform). 자세한 건 [`deploy/lambda/README.md`](deploy/lambda/README.md).

## 문서
- 크롤링 플로우: [`docs/crawl-flow.md`](docs/crawl-flow.md)
- 데이터 포맷: [`docs/data-formats.md`](docs/data-formats.md)

## 구조
```
kbo_collector/   # 코어: config, fetch, sink, journal, naver, community, run ...
config/          # targets.yaml (커뮤니티 크롤 대상)
deploy/lambda/   # 서버리스 배포 (핸들러 + Dockerfile + Terraform)
notebooks/       # 실행/스모크 노트북
tests/           # 단위·통합 테스트
docs/            # 플로우/데이터 포맷 문서
```
로컬 실행 시 생기는 `logs/`·`journal/`·`.env`는 gitignore 처리됩니다.
