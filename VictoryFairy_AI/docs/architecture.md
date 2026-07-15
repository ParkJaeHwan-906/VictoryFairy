# 아키텍처 (현재까지의 구조)

## 개요

VictoryFairy_AI는 한국어 텍스트를 **검열 → 형태소·개체명 추출 → 집계**하는 파이프라인이다.
외부 저장소(S3 등) 없이 **로컬 `data/*.txt`·`*.json`을 스테이지 간 저장소**로 쓰는 배치 구조다.

## 전체 데이터 흐름

```
crawled_data.txt (원본 무작위 문장)
   └─[validation] 검열(욕설 필터)──▶ processed_data.txt (통과)  +  discarded_data.txt (폐기+사유)
        └─[analysis] Kiwi 형태소 + NER 개체명 + 사전 후처리
             ├──▶ finished_data.txt   (사람이 읽는 표시용: 원본 : [이름]|[지명]|[기관]|[날짜]|[명사]|[동사])
             └──▶ finished_data.jsonl (집계용 구조화: {문장_id, sent, names, ...})
                  └─[aggregate] 인명 정규화·집계 ──▶ persons_aggregated.json
```

## 구성 요소

| 모듈 | 역할 | 진입점 |
|---|---|---|
| `validation/` | 검열(욕설·비속어 필터) FastAPI 앱 | `validation.main:app` |
| `analysis/` | 형태소 + NER 추출 FastAPI 앱 | `analysis.main:app` |
| `pipeline/` | 파일 기반 배치 러너 | `python -m pipeline.run_*` |
| `data/` | 스테이지 간 파일 저장소 | (러너가 읽고 씀) |
| `docs/` | 구조·전략·모듈 문서 | — |
| `.claude/` | 하네스 설정(Hook·권한) | — |

## 앱 공통 레이어 구조 (validation·analysis 동일)

```
<app>/
├── main.py            # FastAPI 앱 생성(create_app)
├── core/              # 설정·데이터 로더·전처리
│   ├── config.py      # pydantic-settings
│   └── data/          # 외부 JSON 데이터(사전 등)
├── api/
│   ├── router.py      # 라우터 통합
│   └── routes/        # 엔드포인트
├── schemas/           # pydantic 요청/응답 스키마
└── services/          # 비즈니스 로직(핵심)
```

## 기술 스택

| 목적 | 기술 |
|---|---|
| 웹 프레임워크 | FastAPI + uvicorn |
| 스키마/설정 | pydantic, pydantic-settings |
| 형태소 분석 | kiwipiepy (Kiwi) |
| 개체명 인식(NER) | transformers + torch, 모델 `Leo97/KoELECTRA-small-v3-modu-ner` |

## 핵심 설계 원칙

- **검열은 정규화본, 형태소·NER은 원문 기준**으로 처리한다(표면형 훼손 방지).
- 개체명 보정은 **코드가 아닌 사전(JSON) 후처리**로 한다 — 값만 추가하면 반영.
- 근거 설계 문서: `/Users/hwannee/Downloads/TalkFile_ai-filter-pipeline-design.md.md`
