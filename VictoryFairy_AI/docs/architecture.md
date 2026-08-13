# 아키텍처 (현재까지의 구조)

## 개요

VictoryFairy_AI는 KBO 커뮤니티 텍스트를 **패턴 검열 → LLM 2차 검열**하는 배치 파이프라인이다.
**S3를 스테이지 간 저장소**로 쓴다.

> ⚠️ **형태소·개체명 추출(analysis)은 코드가 남아 있으나 배선에서 빠져 있다.** 아래 "유산" 절 참고.
> 이 문서에서 `data/*.txt` 기반으로 서술된 부분은 그 유산 경로에만 해당한다.

## 전체 데이터 흐름 (현행)

```
크롤러 ──▶ S3 community/{source}/{date}/{postId}.json
   └─[pipeline.run_validation] 패턴 검열(사전·정규식)
        ├──▶ validation/pattern/success/...   (통과 본문 + 통과 댓글 재조립)
        ├──▶ validation/pattern/failed/...    (폐기 사유)
        └──▶ validation/pattern/_manifest/... (완결 마커 — 멱등 skip)
             └─[pipeline.run_bedrock] LLM 2차 검열(문맥 욕설·광고/스팸·야구 무관)
                  ├──▶ validation/bedrock/success/...
                  ├──▶ validation/bedrock/failed/...
                  └──▶ validation/bedrock/_manifest/...
```

## 구성 요소

| 모듈 | 역할 | 진입점 |
|---|---|---|
| `validation/` | 1차 검열(욕설·비속어 필터) FastAPI 앱 | `validation.main:app` |
| `bedrock/` | 2차 검열(AWS Bedrock LLM). **라우트 없는 서비스 모듈** | `bedrock.services.judge` |
| `analysis/` | 형태소 + NER 추출 FastAPI 앱 (**배선에서 빠짐**) | `analysis.main:app` |
| `pipeline/` | S3 기반 배치 러너 | `python -m pipeline.run_*` |
| `data/` | 유산 경로(분석·집계)의 파일 저장소 | (러너가 읽고 씀) |
| `docs/` | 구조·전략·모듈·요구사항 문서 | — |
| `.claude/` | 하네스 설정(Hook·에이전트·권한) | — |

## 유산 — 분석·집계 경로 (현재 미배선)

```
processed_data.txt (공급자 없음 — 예전엔 run_validation 이 채웠다)
   └─[analysis] Kiwi 형태소 + NER 개체명 + 사전 후처리
        ├──▶ finished_data.txt   (표시용: 원본 : [이름]|[지명]|[기관]|[날짜]|[명사]|[동사])
        └──▶ finished_data.jsonl (집계용: {문장_id, sent, names, ...})
             └─[aggregate] 인명 정규화·집계 ──▶ persons_aggregated.json
```

코드·파일은 보존돼 있어 개별 실행은 가능하지만 **입력 공급이 끊겨 있다.** 재연결은 미도입.

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
