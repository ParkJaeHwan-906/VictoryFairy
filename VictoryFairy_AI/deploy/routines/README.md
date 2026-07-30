# 위키 빌더 · 퀴즈 생성기 routine 운영 가이드

`wiki-builder/ROUTINE.md`·`question-gen/ROUTINE.md`를 Claude Code cloud
routine으로 운영에 올리는 절차. IAM 사용자 발급부터 routine 등록·모니터링까지
다룬다. **2025 시즌 백필은 이번 작업에서 보류**했다 — 아래 "4. 2025 백필
절차" 참고.

## 1. IAM 사용자 생성 · 정책 attach · access key 발급

routine 2개는 같은 S3 버킷의 서로 겹치는 prefix만 쓰므로 IAM 사용자 1개를
공유한다(`iam-policy-routines.json`, S3 전용 최소 권한 — 읽기
`question-source/*`·`kbo-records/*`·`validation/bedrock/success/*`, 읽기+쓰기
`wiki/*`·`quiz-candidates/*`, 조건부 `ListBucket`).

실버킷명은 여기에 하드코딩하지 않는다 — `py-collector/.env`의
`COLLECTOR_S3_BUCKET` 값을 그대로 쓴다(현재 dev 버킷은
`victoryfairy-crawl-dev`, `py-collector/.env`는 `.gitignore` 처리됨 — 직접
확인할 것).

```bash
# 0) 버킷명 확인 (하드코딩하지 않는다)
BUCKET=$(grep '^COLLECTOR_S3_BUCKET=' py-collector/.env | cut -d= -f2)
echo "target bucket: $BUCKET"

# 1) IAM 사용자 생성
aws iam create-user --user-name victoryfairy-routine

# 2) 정책 JSON의 ${BUCKET} 치환 후 인라인 정책으로 attach
sed "s/\${BUCKET}/$BUCKET/g" deploy/routines/iam-policy-routines.json \
  > /tmp/iam-policy-routines.rendered.json
aws iam put-user-policy \
  --user-name victoryfairy-routine \
  --policy-name victoryfairy-routine-s3 \
  --policy-document file:///tmp/iam-policy-routines.rendered.json

# 3) access key 발급 (한 번만 출력됨 — 안전한 곳에 즉시 보관)
aws iam create-access-key --user-name victoryfairy-routine
```

발급된 `AccessKeyId`·`SecretAccessKey`는 2번 섹션에서 routine env로 등록한다.
평문으로 리포·슬랙·이슈에 남기지 않는다.

## 2. routine 등록 (Claude Code cloud schedule)

두 routine 모두 아래 공통 env를 쓴다:

| env | 값 |
|---|---|
| `S3_BUCKET` | 1번에서 확인한 `$BUCKET` |
| `AWS_ACCESS_KEY_ID` | 1번에서 발급한 access key |
| `AWS_SECRET_ACCESS_KEY` | 1번에서 발급한 secret key |
| `AWS_DEFAULT_REGION` | `ap-northeast-2` |

### 2-1. 위키 빌더

- **스케줄**: 화·금 06:00 KST (`0 21 * * 1,4` UTC cron — KST는 UTC+9이므로
  전날 21:00 UTC에 트리거)
- **모델**: Sonnet 5
- **작업 디렉토리**: 리포 루트에서 `VictoryFairy_AI/`
- **프롬프트**: "`VictoryFairy_AI/wiki-builder/ROUTINE.md`를 처음부터 끝까지
  그대로 실행하라. 절차·실패 처리 규칙을 이 문서에서 벗어나지 말고 그대로
  따르라."
- **env**: 위 공통 env 4개

### 2-2. 퀴즈 생성기

- **스케줄**: 매일 08:50 KST (`50 23 * * *` UTC cron — 전날 23:50 UTC).
  `game_schedule` export(08:30 KST, infra 스케줄 — 3번 섹션 참고)보다 뒤로
  잡아 오늘 매치업이 이미 S3에 있는 상태에서 실행되게 한다.
- **모델**: Sonnet 5
- **작업 디렉토리**: 리포 루트에서 `VictoryFairy_AI/`
- **프롬프트**: "`VictoryFairy_AI/question-gen/ROUTINE.md`를 처음부터 끝까지
  그대로 실행하라. 절차·실패 처리 규칙을 이 문서에서 벗어나지 말고 그대로
  따르라."
- **env**: 위 공통 env 4개

두 routine 모두 `py-collector/.venv/bin/python`을 실행기로 쓰므로, routine
컨테이너/워크스페이스에 리포 클론이 온전히 존재하고 `py-collector/.venv`가
이미 구성돼 있어야 한다(ROUTINE.md 사전 조건 참고). venv가 없으면 첫 실행
전에 `cd py-collector && python3 -m venv .venv && .venv/bin/pip install
pyyaml`로 최소 의존성(PyYAML)만 준비한다.

## 3. 모니터링

- **routine 실패 노티**: Claude Code cloud schedule 대시보드에서 각
  routine의 최근 실행 상태를 확인한다(실패 시 대시보드 알림). 이 문서는
  대시보드 자체의 배선을 다루지 않는다 — routine 등록 시 알림 채널이 이미
  연결돼 있는지 등록 화면에서 확인할 것.
- **위키 빌더 실행 로그**: `s3://$BUCKET/wiki/_meta/builder-runs/{ISO}.json`
  — 마커가 실행마다 하나씩 쌓인다(`runAt`·`postsProcessed`·`playersUpdated`·
  `skipped`). 마지막 마커가 예상 주기(화·금)보다 오래됐으면 routine이 멈췄다는
  신호.
  ```bash
  aws s3 ls "s3://$BUCKET/wiki/_meta/builder-runs/" | tail -5
  ```
- **퀴즈 생성기 적재 확인**: 오늘자 `quiz-candidates/{date}/`에 파일이
  쌓였는지 확인한다(파이프라인 목표는 일일 10문항 — 며칠 연속 0건이면 조사
  필요).
  ```bash
  TODAY=$(date -u +%Y-%m-%d)
  aws s3 ls "s3://$BUCKET/quiz-candidates/$TODAY/" --recursive | wc -l
  ```
- **casebook·템플릿 제안**: 퀴즈 생성기가 매 실행 `wiki/_meta/casebook/`·
  `wiki/_meta/template-proposals/{date}.md`를 갱신한다. 사람이 주기적으로
  훑어 리포의 `question-gen/casebook/`에 수동 반영하고(routine은 리포를
  건드리지 않음), 제안된 신규 템플릿은 검토 후 사람이 직접
  `question-gen/config/question-templates.yaml`에 추가한다.

## 4. 2025 시즌 백필 절차 (보류)

**현재 상태: 보류.** 이 작업(Task 11) 진행 중 SSH 터널(로컬 `127.0.0.1:3306`
→ 운영 DB)이 닫혀 있어 실행 전 확인 쿼리조차 돌릴 수 없었다
(`docker exec vf-local-mysql mysql -h host.docker.internal -P 3306 ...` →
`ERROR 2003`). 아래는 터널이 열렸을 때 실행할 절차를 문서화만 한 것이며,
**아직 실행하지 않았다.** `question-gen/config/question-templates.yaml`의
`YOY_TEAM` 템플릿은 `enabled: false`로 유지된다.

### 전제 조건

- 로컬 MySQL 접근은 SSH 터널(`127.0.0.1:3306`)을 통한 원격 DB 접속이다(로컬
  도커 컨테이너가 아님 — 도커 컨테이너는 그림자 환경). 아래 명령을 실행하기
  전에 터널을 열어야 한다.
- KBO 2025 정규시즌 개막일은 2025-03-22. 종료일은 백필 실행 시점에 웹에서
  실측 확인한다(정규시즌 종료일이 매년 다르고, 포스트시즌 포함 여부도
  확인해야 한다).

### 절차

```bash
# 1) 터널이 열린 상태에서 운영 DB에 2025 데이터가 있는지 확인
docker exec vf-local-mysql mysql -h host.docker.internal -P 3306 -uvf -pvfpass \
  -e "SELECT YEAR(game_date) y, COUNT(*) c FROM victoryfairy.games GROUP BY y"

# 2) 2025 행이 없으면 백필 (종료일은 위 "전제 조건"에서 확인한 값으로 치환)
cd VictoryFairy_AI/py-collector
COLLECTOR_DB_HOST=127.0.0.1 COLLECTOR_DB_PORT=3306 python -m kbo_collector.run records \
  --from 2025-03-22 --to <2025 정규시즌 종료일>

# 3) 전체 game_result envelope export (date 미지정 = games 전체 재export)
python -m kbo_collector.run export --target game_result

# 4) 대략적인 건수 확인 (2025+2026 경기 수 근사)
BUCKET=$(grep '^COLLECTOR_S3_BUCKET=' .env | cut -d= -f2)
aws s3 ls "s3://$BUCKET/question-source/game_result/" --recursive | wc -l
```

### 백필 완료 후 — YOY_TEAM 활성화

백필이 성공적으로 끝나 2025 데이터가 `question-source/game_result/`에
반영되면 다음을 수행한다:

1. `question-gen/config/question-templates.yaml`에서 `YOY_TEAM` 항목의
   `enabled: false   # 2025 시즌 백필 완료 후 활성화 (스펙 리스크 참조)` 줄을
   지운다(`enabled: false` 자체를 제거 — YAML 기본값은 활성화).
2. `aggregate_stats.py`를 드라이런해 `yoy` 필드가 `null`이 아닌지 확인한다:
   ```bash
   cd VictoryFairy_AI
   py-collector/.venv/bin/python question-gen/scripts/aggregate_stats.py \
     --envelopes-dir <2025+2026 game_result 스냅샷 디렉토리> \
     --kbo-dir <kbo-records 스냅샷 디렉토리> \
     --out-dir /tmp/aggregate-dryrun --date "$(date -u +%Y-%m-%d)"
   python3 -c "import json; d=json.load(open('/tmp/aggregate-dryrun/season.json')); print(d.get('yoy'))"
   ```
   `yoy`가 `null`이면 2025 데이터가 아직 통계 재집계 창에 들어오지 않았다는
   뜻이므로 활성화를 보류한다.
3. 위 두 단계가 모두 통과하면 커밋(`YOY_TEAM` 활성화 커밋은 이 커밋과
   분리해 별도로 남긴다 — 백필 완료 시점이 이 문서 작성 시점과 다르므로).

## 5. 운영 전제 조건 (발견된 갭)

이번 Task 11 구현 및 선행 Task 1~10 드라이런 과정에서 실측으로 확인된,
routine을 실제 운영에 올리기 전에 알아둬야 할 갭 목록이다.

1. **`question-source/player_profile/` S3 완전 공백** — 위키 빌더
   ROUTINE.md 2단계(참조 데이터 동기화)의 필수 입력이다. export 리더
   자체는 존재한다(`py-collector/kbo_collector/exports/exporter.py::
   read_player_profiles`, 실행 명령은
   `python -m kbo_collector.run export --target player_profile`)하나, 이
   버킷에 한 번도 실행된 적이 없다(2026-07-30 실측, `aws s3api
   list-objects-v2 --prefix question-source/player_profile/` → 0 keys).
   위키 빌더 routine을 등록하기 전에:
   - **최소 1회 수동 실행**으로 초기 파티션을 채운다.
   - **주기 실행(주 1회 권장)**을 스케줄링한다 — EventBridge 규칙 추가는
     dev_infra 소관이다(이 리포·이 worktree의 범위 밖).
   - 이 갭이 해소되지 않은 채로 위키 빌더가 돌면 ROUTINE.md 2단계가
     안전하게 감지해 3~4단계(선수 문서 병합·trending)를 스킵하고 빈 실행
     로그만 남긴다(문서화된 정상 동작 — 실패로 잡히지 않으니 모니터링에서
     "왜 매번 갱신 문서가 0건인지"는 이 갭으로 설명된다).
2. **`question-source/game_result/` 파티션 1개뿐** — 지금까지 수동 1회
   실행 이력만 있고 일일 export 스케줄이 없다. 일일 export 스케줄은
   infra 브랜치 `sotaeho/infra/feat-quiz-pipeline-schedules`(커밋
   `e9e08a8`, worktree `/Users/sotaeho/PycharmProjects/VictoryFairy-infra-quiz`)
   에 EventBridge 규칙 3종으로 이미 준비돼 있다:
   - `kbo_records` — 07:00 KST
   - `game_schedule` — 08:30 KST
   - `export --target game_result` — 04:00 KST

   **이 규칙들은 아직 PR·`terraform apply`가 되지 않은 대기 상태다.** 이
   상태로는 퀴즈 생성기 routine이 매일 신선한 `game_result` 데이터를 받지
   못하므로, 위 infra PR이 머지·apply되기 전에 퀴즈 생성기 routine을
   실운영 스케줄로 올리면 매일 같은(또는 텅 빈) 스냅샷만 보게 된다.
3. **`question-gen/config/all-time-records.yaml`은 v0 초안** — Task 9가
   생성한 시드로, 이미 알려진 이슈가 있다(예: 통산 타자 카테고리의 값 열이
   헤드라인 통계인 타율이 아니라 타수로 뽑힘 — 원본 표 헤더 순서 때문). 이
   파일을 위키 빌더가 렌더링해 소비하기 전에 **사람 검수가 필수**다. 검수
   없이 routine 등록만 하면 `all-time-records.md`가 부정확한 통계로
   렌더링될 수 있다.
4. **`kbo-records/`의 `top5`·`record-correct`는 상시 미생성** — 원본 KBO
   기록 게시판의 마크업이 두 카테고리와 비호환이라(Task 2 확인) 크롤러가
   이 두 카테고리 스냅샷을 만들지 못한다. `expectation-week`는 스냅샷은
   생성되지만 내용이 게시판 목록(다가오는 이벤트 목록)이라 마일스톤 데이터
   형태가 아니어서 `MILESTONE_WATCH` 같은 템플릿이 실사용할 수 없다(Task
   10 확인). 두 갭 모두 크롤러 재작업 없이는 해소되지 않으므로, 이 세
   카테고리에 의존하는 템플릿은 당분간 후보에서 자연 제외된다고 가정하고
   운영해야 한다.

---

이 문서가 다루지 않는 것: routine 등록 UI 자체의 배선(대시보드 접근 권한
등), Terraform apply·Lambda 이미지 배포(배포 소유자 수행), BE
`quiz-candidates` 계약 공유(스펙 4.3 문서 링크 전달만 필요).
