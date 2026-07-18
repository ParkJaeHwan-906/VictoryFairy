# CLAUDE.md — py-collector 작업 가이드

> Claude Code가 이 디렉토리에서 작업할 때 자동 로드되는 가이드입니다. **사람용 소개는
> [`README.md`](./README.md)**, 여기는 "무엇을 어디서 고치고 무엇을 조심하나"에 집중합니다.
> 내용 중복 대신 아래 문서로 링크합니다.

## 이 패키지가 뭔지

`py-collector`는 **KBO 데이터 수집기**입니다. 세 원천(네이버 스포츠 API / KBO 공식 사이트 /
DCInside·FMKorea)을 크롤·호출해서

1. 경기 원본 JSON·커뮤니티 글을 **S3 브론즈**에 멱등 적재하고,
2. 로스터·박스스코어를 **MySQL**에 정규화 적재하며,
3. 이것들을 소스 무관 **question-source envelope(v1)** 로 재포장해 S3 `question-source/`에 올려
   질문생성 AI에 인계합니다.

수집 코어(`kbo_collector/`)는 오케스트레이션 비의존 순수 파이썬이고, CLI(`python -m kbo_collector.run`)·
Lambda·로컬 스크립트가 이를 얇게 호출합니다.

## 문서 맵 — 언제 무엇을 읽나

| 문서 | 한 줄 | 언제 읽나 |
|---|---|---|
| [`docs/directory-structure.md`](./docs/directory-structure.md) | 파일 배치 + "어디 고치면 뭐 바뀌나" | **처음 진입 시**·어느 파일을 열지 모를 때 |
| [`docs/crawl-flow.md`](./docs/crawl-flow.md) | 잡→코어→싱크 플로우(다이어그램) | 수집 실행 순서·체크포인트·dead-letter 이해할 때 |
| [`docs/data-formats.md`](./docs/data-formats.md) | 적재 JSON 필드(schedule/result/relay/record/RawPost) | 네이버 원본·RawPost 필드 확인할 때 |
| [`docs/envelope-format.md`](./docs/envelope-format.md) | question-source 최종 산출물(12필드·docType 4종) | **소비자 계약**·export·새 docType 만질 때 |
| [`docs/data-pipeline-requirements.md`](./docs/data-pipeline-requirements.md) | 요구사항(R1/R2)·아키텍처 갭 검토 | 소스 확장·스키마 소유권 결정 등 설계 판단 시 |
| [`docs/current-crawl-overview.md`](./docs/current-crawl-overview.md) | 전체 개요(미팅용)·MySQL 스키마 표 | 큰 그림·테이블 스키마 한 장 훑을 때 |

## 작업 시 주의사항

- **FMKorea는 주거 IP에서만.** FMKorea는 AWS 데이터센터 IP에 **HTTP 430**을 반환합니다. Lambda 크롤
  대상(`config/targets.yaml`)엔 DCInside만 있고, FMKorea는 로컬(맥북) 스크립트(`deploy/local/`)로만 돕니다.
  다시 430이 나면 `kbo_collector/fetch.py`의 `BROWSER_UA` 버전을 올리세요.
- **S3 버킷은 `COLLECTOR_S3_BUCKET` 환경변수.** 코드에 하드코딩 금지. 로컬은 LocalStack 엔드포인트
  (`COLLECTOR_S3_ENDPOINT`) 사용(`.env.example` 참고). 설정은 전부 `kbo_collector/config.py` 경유.
- **PII salt는 로컬·Lambda가 동일해야.** `COLLECTOR_PII_SALT`가 다르면 같은 댓글 작성자가 다른 토큰으로
  해시돼 매핑이 깨집니다. salt 실값은 커밋하지 말 것(마스킹: `kbo_collector/masking.py`).
- **소스 추가 = `sources/`에 모듈 1개(`@register`) + `sources/__init__.py` import 1줄.** `run.py`·소비자는
  건드리지 않습니다(소스 계약·이유는 `envelope-format.md`·`directory-structure.md`). 필요 시 `config.py`에
  설정 1줄.
- **DB 잡은 SSH 터널 MySQL로만.** `records`·`registrations`·`teams`·`export`는 MySQL에 씁니다. `127.0.0.1:3306`은
  로컬 도커가 아니라 **SSH 터널 너머 원격 DB**입니다. **Lambda는 MySQL에 접근하지 않습니다**(S3 전용) —
  DB 잡은 DB 접근 가능한 호스트의 CLI/크론에서 실행하세요.
- **코드 파일 수정은 신중히.** `kbo_collector/`·`deploy/`·`tests/`는 실행 코드입니다. 문서만 고칠 때는
  `docs/`·`README.md`·이 파일만 만지세요.

## 자주 쓰는 명령

```bash
pip install -e ".[dev]" && pytest -q          # 설치 + 테스트
python -m kbo_collector.run all --date 2026-07-08      # 하루 경기 3종 + 커뮤니티 (S3)
python -m kbo_collector.run records --from 2026-03-28 --to 2026-10-01   # 박스스코어 백필 (MySQL)
python -m kbo_collector.run export --target game_result --date 2026-07-08  # question-source 적재
```

> 잡 목록·옵션은 `run.py`의 argparse(`schedule/result/relay/game/community/all/teams/registrations/records/collect/export`),
> 실행 흐름은 `docs/crawl-flow.md` 참고. 백필(경기·FMKorea 커뮤니티 구간)은 `directory-structure.md`
> "자주 하는 변경" 절과 `deploy/local/backfill_fmkorea.sh` 참고.
</content>
