# Bedrock 러너 설계 — 퀴즈 생성기·위키 빌더의 실행체 전환

> 상태: 초안 (2026-08-03). 선행 스펙
> [2026-07-28 LLM 위키+퀴즈 설계](2026-07-28-llm-wiki-quiz-generation-design.md)의
> **실행체 부분만** 대체한다 — 데이터 계약(S3 경로·envelope·quiz-candidates JSON)·
> 결정적 스크립트·프롬프트 문서·카탈로그는 전부 그대로 유지된다.

## 1. 배경: 왜 전환하는가 (2026-08-03 실측)

원 설계는 두 LLM 잡(퀴즈 생성기·위키 빌더)을 Claude Code 클라우드 루틴으로
돌리는 것이었다. 2026-08-03 crawl-local 검증에서 실측된 문제:

1. **클라우드 샌드박스에서 AWS 접근 실패** — 등록·발사까지는 됐으나 IAM access
   key 사용 이력이 `N/A`(한 번도 인증 안 됨). 이그레스 제한으로 추정되며, 45분
   내 `quiz-candidates/` 산출 없음.
2. **시크릿 주입 경로 부재** — 루틴 등록 API에 env/secret 필드가 없어 AWS 키가
   루틴 프롬프트에 평문으로 들어갔다(해당 키는 검증 직후 폐기함).
3. **개인 계정 종속** — 루틴·로그가 등록자 개인 claude.ai 계정에 귀속. 팀
   관측·인수인계 불가.
4. **비용·속도 구조** — 에이전트 세션은 "일하는 과정"(도구 왕복·파일 탐색)에도
   토큰을 쓴다. 회당 수십만 토큰·15~40분. 구조화 호출은 회당 ~4만 토큰·2~4분.

전환 후 구조는 검열 파이프라인(`victoryfairy-dev-refine-bedrock`)이 이미 운영
중인 패턴(잡 → Bedrock API → S3)과 동일하다.

## 2. 아키텍처

```
EventBridge / CronJob (스케줄)
   └─▶ 러너 컨테이너 (ECR 이미지 victoryfairy-quiz-runner)
         ├─ entrypoint bash  ← ROUTINE.md 셸 블록 이식 (sync·스크립트·게이트·업로드)
         ├─ 결정적 python    ← aggregate_stats.py · validate_candidates.py ·
         │                      compile_graph.py (기존 파일 그대로 COPY)
         └─ runner python    ← LLM 필요 지점만 Bedrock InvokeModel 호출
               └─▶ Amazon Bedrock (Claude) ──▶ S3 (기존 계약 그대로)
```

- **자격증명**: IAM Role(EKS면 IRSA, Fargate면 task role) — access key 발급
  자체가 사라진다. 정책 = 기존 `deploy/routines/iam-policy-routines.json`
  (S3 최소권한) + `bedrock:InvokeModel`.
- **관측**: CloudWatch Logs + 기존 실행 로그 계약(`wiki/_meta/builder-runs/`,
  quiz 실행 요약) 유지.

## 3. AI 호출 설계 — 4콜로 고정

LLM이 반드시 필요한 지점만 구조화 호출로 남긴다. 에이전트 루프·도구 사용 없음.

| 콜 | 잡 | 모델 | 입력 | 출력 | 빈도 |
|---|---|---|---|---|---|
| C1 generate | 퀴즈 | **Sonnet급** | generation-rules.md + casebook + 선택된 (템플릿×엔티티)별 바인딩 데이터 | 후보 15개 (스펙 §4.3 JSON 배열, 가제 quizId) + 신규 템플릿 제안 0~2건 | 일 1회 |
| C2 judge | 퀴즈 | **Haiku급** | 후보 15개 + 최근 7일 출제 요약 + banned-topics + verification-pass.md 4·5단계 규칙 | 후보별 {의미중복 여부, 안전 위반 여부, 재미 1~5, 난이도 재분류, 사유} | 일 1회 |
| C3 merge | 위키 | **Sonnet급 고정** | merge-rules.md + 기존 문서 + 게시글 다이제스트 + 프로필/밈 시드 | 갱신 문서 전문 + 스킵 목록(사유) | 주 2회 × 선수당 1콜 |
| C4 trending | 위키 | Haiku급 | 결정적 키워드 카운트 + 상위 토픽 근거 게시글 | trending.md 본문 | 주 2회 |

- 모델 배정 원칙: **문구 품질이 제품인 곳(C1)과 계약 준수가 까다로운 곳(C3)만
  Sonnet급**, 분류·요약(C2·C4)은 Haiku급. C1의 Haiku 강등은 casebook few-shot
  축적 후 A/B로만 검토한다.
- **프롬프트 캐싱**: C1·C2의 고정부(rules·casebook·카탈로그)는 Bedrock prompt
  caching으로 재사용 — 입력이 출력보다 ~3배 크므로 효과가 크다.
- **구조화 출력**: 응답은 JSON 강제. 파싱 실패 시 1회 재시도, 재실패 시 그날
  업로드 생략(기존 실패 처리 규칙과 동일).

## 4. LLM에서 코드로 내려오는 것

원 ROUTINE.md에서 "이 세션이 직접 수행"이던 단계 중 다음은 결정적 코드가 된다:

- **템플릿 선택**: enabled 필터 + needs 데이터 존재 확인 + 최근 7일 templateId
  편중 카운트 → 규칙으로 완전 대체 ("의외성 선호"는 제거, 회전은 편중 카운트가
  담당)
- **evidence 원문 대조**: source 경로 해석 → 대상 파일에서 quote substring 검사
  (2026-08-01 수동 가동 때 스크립트로 실증한 방식). C2의 판단과 별개로 항상 실행
- **quizId 부여·난이도 비율 선별·casebook append**: C2의 채점 결과를 입력으로
  받는 결정적 후처리
- **위키 빌더 후보 그룹핑**: 기존 3-1 스니펫 그대로

기존과 동일하게 유지되는 금지: LLM은 수치를 계산하지 않고, evidence를 창작하지
않고, 카탈로그를 수정하지 않는다.

## 5. 실행체·스케줄

| 잡 | 스케줄 | 실행체 |
|---|---|---|
| 퀴즈 러너 | 매일 08:50 KST | EKS CronJob(권장) 또는 Fargate 스케줄드 태스크 |
| 위키 러너 | 화·금 06:00 KST | 동일 이미지, 다른 entrypoint 인자 |

- EKS CronJob 권장 근거: 클러스터가 이미 운영 중, 실행 시간 제약 없음(위키
  러너는 선수 수에 따라 수십 분 가능), IRSA로 자격증명 처리. **최종 선택은
  dev_infra 소유자와 확정한다** (Lambda 15분 제한 때문에 Lambda는 후보에서 제외).
- 컨테이너 이미지 1개(`victoryfairy-quiz-runner`)를 두 잡이 공유한다 — 내용물:
  python3 + awscli + `question-gen/` + `wiki-builder/` + `py-collector`의 스크립트
  의존성(PyYAML). 수집기 이미지(`kbo-collector`)와는 분리한다(책임·배포 주기가
  다름).

## 6. 문서의 지위 변화

- `question-gen/ROUTINE.md`·`wiki-builder/ROUTINE.md`는 "클라우드 세션 실행
  지침"에서 **러너의 스펙 문서**로 역할이 바뀐다. 러너 구현과 문서가 어긋나면
  문서를 먼저 고치고 구현을 맞춘다(문서가 진실의 원천 — 위키와 같은 원칙).
- prompts/(generation-rules·verification-pass·merge-rules)와 casebook은 **러너가
  런타임에 읽어 프롬프트에 삽입**한다 — 품질 개선이 파일 편집이라는 원칙 유지.

## 7. 실패 처리 (기존 규칙 계승)

- 어느 단계든 실패 → 그날 업로드 생략 + CloudWatch 로그 + (기존 노티 채널) 알림.
  폴백 퀴즈는 BE/어드민 소관 그대로.
- Bedrock 스로틀/일시 오류 → 지수 백오프 재시도 2회.
- 업로드는 quizId·문서 단위 멱등이라 재실행 안전 — 기존과 동일.

## 8. 비용·속도 추정

| 항목 | 추정 |
|---|---|
| 퀴즈 러너 LLM (C1 Sonnet + C2 Haiku, 캐싱 전) | ~일 200원 |
| 위키 러너 LLM (C3 Sonnet ×10~30명 + C4) | ~회 1,200원 × 주 2회 |
| 인프라 (EKS CronJob 기준) | 사실상 0 (기존 노드) |
| 월 합계 | **$10~15 수준** |
| 퀴즈 생성 소요 | **2~4분** (기존 에이전트 방식 15~40분) |

## 9. 마이그레이션 단계

1. 러너 구현 (entrypoint + runner 모듈 + Dockerfile) — 기존 스크립트 재사용
2. **crawl-local 검증**: 2026-08-03 현재 crawl-local에 위키·기록실·경기 데이터가
   전부 시딩돼 있고 수집 Lambda(EventBridge 07:00/08:30 KST)도 가동 중이므로,
   러너를 로컬 docker run → Fargate/CronJob 1회 실행으로 단계 검증
3. dev 전환: S3_BUCKET=victoryfairy-crawl-dev + 스케줄 등록 (terraform,
   dev_infra 협의)
4. 클라우드 루틴 잔재 정리: `vf-quiz-generator-local-test`는 비활성화됨 —
   claude.ai/code/routines에서 완전 삭제(수동)

## 10. 오픈 퀘스천

1. **EKS CronJob vs Fargate** — dev_infra 소유자와 확정 (기존 "DB 적재 Lambda vs
   EKS CronJob" 결정 기록과 정합 확인)
2. **Bedrock 모델 가용성** — ap-northeast-2에서 사용할 Sonnet/Haiku 모델 ID·쿼터
   확인 (검열 파이프라인이 쓰는 모델·리전 구성을 그대로 따르는 것이 1안)
3. **casebook 리포 반영 주기** — 러너는 S3 `wiki/_meta/casebook/`만 갱신(기존
   규칙). 사람이 리포에 반영하는 주기를 스프린트 리듬에 맞출지
4. **선행 조건 승계** — all-time-records.yaml v0 사람 검수, player_profile 주기
   export, EventBridge 테라폼 3종은 이 전환과 무관하게 여전히 필요 (기존 갭)
