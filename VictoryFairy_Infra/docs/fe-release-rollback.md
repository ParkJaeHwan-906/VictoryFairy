# FE 릴리스 롤백

FE 롤백은 **재빌드가 아니라 파일 하나를 되돌려 놓는 것**이다. 수 초 안에 끝나고 CI·빌드·git 어느
것도 필요하지 않다.

**배포 직후 실패는 사람이 개입하지 않는다** — 워크플로가 스모크 테스트로 잡아 자동으로 되돌리고
추적 티켓까지 남긴다(§자동 롤백). 아래 수동 절차는 그 자동화가 놓친 경우, 또는 배포가 끝난 뒤
한참 지나 문제가 드러난 경우를 위한 것이다.

## 급할 때 — 이것만 보면 된다

```bash
B=victoryfairy-dev-fe

# 1) 배포 이력. 이름이 UTC 타임스탬프로 시작하므로 정렬 = 시간순.
#    끝에서 두 번째가 '이전 버전' 이다.
aws s3 ls s3://$B/releases/

# 2) 되돌리기 (이게 전부다)
aws s3 cp s3://$B/releases/20260807T120000Z-aecc7b1/index.html s3://$B/index.html

# 3) 확인 — 번들 파일명이 바뀌었는지 본다
# 해시 문자 클래스에 '_' 와 '-' 가 있어야 한다 — Vite 해시는 base64url 이다.
curl -s https://victoryfairy.com/ | grep -o 'assets/index-[A-Za-z0-9_-]*\.js'
```

`index.html` 은 엣지 TTL 이 0 이라 **즉시 반영**된다. 무효화가 필요 없다.

---

## 왜 이것만으로 되는가

세 가지가 맞물려 있다.

**1. 배포마다 아카이브가 남는다.** 워크플로가 `releases/<타임스탬프>-<SHA>/` 에 그 시점의
`index.html` 과 자산 전체를 올린다. 되돌릴 대상이 바이트 단위로 보존된다.

**2. 자산이 루트에 누적된다.** 루트 업로드는 `--delete` 없이 sync 하므로 **모든 버전의 해시 자산이
루트에 함께 남는다.** 그래서 옛 `index.html` 을 되돌려 놓으면 그것이 참조하는 청크가 이미 거기 있다.
`index.html` 하나만 바꾸면 되는 이유가 이것이다.

**3. `index.html` 은 캐시되지 않는다.** 엣지 TTL 0(`modules/cdn` 의 html 캐시 정책) + 브라우저
`no-cache`. 오브젝트를 바꾸는 순간이 곧 전환이다.

```
배포:  dist → releases/<버전>/   (아카이브)
            → 루트               (전환 — 여기가 서비스본)

롤백:  releases/<버전>/index.html → 루트/index.html
       (자산은 이미 루트에 있으므로 손댈 필요가 없다)
```

버전 이름은 `20260807T134500Z-611f044` 형태다.

- **타임스탬프(UTC 고정폭)** — 사전순 정렬이 곧 시간순이라 `aws s3 ls` 결과의 끝에서 두 번째가
  바로 이전 버전이다. 로컬 시간이나 자리수가 들쭉날쭉한 형식(`2026-8-7`)을 쓰면 이 성질이 깨진다.
- **커밋 SHA** — 디렉터리 이름만 보고 어떤 코드인지 알 수 있고, 같은 커밋을 재배포해도 겹치지 않는다.
  ECR 이미지 태그 규약(`${GITHUB_SHA::7}`)과도 이름이 연결된다.

이 구조는 AWS 권고를 따른 것이다 —
[Use file versioning to update or remove content](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/UpdatingExistingObjects.html)
는 파일명이나 **디렉터리 이름에 버전 식별자**(날짜·시각 스탬프 포함)를 넣으라고 권하며, 그러면
만료를 기다릴 필요도 무효화 비용을 낼 필요도 없다고 밝힌다. Vite 의 콘텐츠 해시가 자산에 대해
이미 그 절반을 하고 있었고, 이름을 고정해야 하는 `index.html` 이 남은 공백이었다.

## 자동 롤백 (배포 직후)

`deploy-fe.yml` 이 발행 직후 실서비스 도메인으로 스모크 테스트를 돌리고, 실패하면 **사람 개입 없이**
이전 릴리스로 되돌린 뒤 추적 티켓을 만든다. `deploy-eks.yml` 의 `rollout status` → `rollout undo` 와
같은 규약이다.

```
발행 → 스모크 → (실패) → 이전 릴리스로 복원 → 티켓 발행 → 워크플로 실패
```

### 무엇을 검사하는가

| 검사 | 잡아내는 것 |
|---|---|
| 서비스되는 번들 해시 == 방금 빌드한 해시 | 발행이 실제로 반영됐는지 (엣지에 옛 응답이 남았는지) |
| `/` 200 | 진입점 |
| `/assets/index-*.js` 200 | 번들 도달 가능 |
| `/login` 200 | SPA fallback (딥링크) |
| `/api/actuator/health/readiness` 200 | base path 를 잘못 구웠는지 |
| `/rt/actuator/health/readiness` 200 | 같음 (채팅 경로) |

⚠ **에러율 알람으로는 이 실패를 잡을 수 없다.** 번들이 깨져도 S3·CloudFront 는 200 을 준다.
그래서 "응답이 왔는지" 가 아니라 **"올바른 것이 왔는지"** 를 단정해야 한다. 번들 해시 대조가
그 핵심이고, 이걸 빼면 검증이 사실상 무의미해진다.

`/api`·`/rt` 확인은 FE 범위를 넘는 것처럼 보이지만 넣어 두었다. base path 를 잘못 구우면 **화면은
뜨고 API 만 죽는** 형태로 깨지는데, 2026-08-07 base path 교체 때 실제로 그랬다. 그 유형은 여기서만
잡힌다.

### 발행 전 실패는 롤백하지 않는다

빌드·번들 검증 단계에서 죽으면 루트를 건드리지 않았으므로 **되돌릴 것이 없다.** 전환(루트 발행)이
마지막 단계라서 성립하는 성질이다. 그래서 롤백·티켓 단계는 `steps.publish.outcome == 'success'` 를
함께 확인한다 — 이 조건이 없으면 빌드 실패마다 무의미한 티켓이 쌓인다.

### 티켓

`fe-rollback` 라벨로 이슈가 열리고 실패 사유·버전·커밋·워크플로 링크가 담긴다. 라벨은 워크플로가
없으면 만들고 있으면 갱신하므로 사전 준비가 필요 없다.

티켓을 강제하는 이유는 아래 '주의할 것' 의 첫 항목과 같다 — 롤백은 배포본만 되돌리고 코드는 그대로라,
추적이 없으면 다음 배포에 조용히 취소된다.

⚠ **자동 롤백이 실패한 경우에도 티켓은 열린다.** 그때가 오히려 더 급하므로 본문 첫 줄이 경고로 바뀐다.
최초 배포처럼 이전 릴리스가 없으면 되돌릴 대상이 없어 자동 롤백이 실패한다.

### 한계

배포 직후(워크플로가 도는 몇 초~1분)만 본다. 그 창이 닫힌 뒤는 아래 상시 감시가 맡는다.

## 상시 감시 (배포 이후)

`modules/fe-watchdog` 가 배포와 무관하게 계속 지켜본다.

```
EventBridge(1분) → 점검 Lambda → CloudWatch 커스텀 지표(VictoryFairy/Watchdog)
                                    ↓
                          알람 (연속 3회 실패 = 3~4분 내 감지)
                                    ↓
                                SNS 토픽
                                    ↓
                          대응 Lambda
                             ├ FE 알람 → S3 롤백 + GitHub Issue
                             └ 항상    → Slack 알림
```

### 무엇을 보는가

| 지표 | 검사 | 알람 시 동작 |
|---|---|---|
| `FeHealthy` | `/` 200 → **참조된 번들을 실제로 받아봄** → `/login` 200 | **자동 롤백** + 티켓 + Slack |
| `ApiHealthy{Target=user}` | `/api/actuator/health/readiness` 200 | Slack 알림만 |
| `ApiHealthy{Target=quiz}` | `/rt/actuator/health/readiness` 200 | Slack 알림만 |
| `FeLatency`·`ApiLatency` | 응답 시간 | 알람 없음 (추이 관찰용) |

⚠ **BE 실패에는 FE 롤백을 하지 않는다.** 원인이 BE 인데 FE 를 되돌리면 정상 FE 만 잃는다.
그래서 지표를 나눠 발행하고 알람도 갈라 두었다. 대응 Lambda 는 알람 **이름**으로 분기한다.

### 왜 CloudFront 에러율 알람이 아닌가

FE 번들이 깨져도 S3·CloudFront 는 **200** 을 준다. JS 오류는 브라우저에서 터지므로 서버 측
4xx/5xx 신호가 발생하지 않는다. 그래서 "응답이 왔는지" 가 아니라 **"올바른 것이 왔는지"** 를
단정하는 능동 점검이어야 한다. `index.html` 이 가리키는 자산을 실제로 받아보는 것이 그 핵심이고,
이 확인을 빼면 자산이 사라져도 통과해 점검이 무의미해진다.

### 왜 ALB 타깃그룹 지표를 쓰지 않았나

`UnHealthyHostCount` 가 가장 직접적인 신호지만, 타깃그룹은 AWS Load Balancer Controller 가
소유해 이름(`k8s-victoryf-userapp-391a85ab29`)이 Ingress 재생성 때 바뀐다. Terraform 이 안정적으로
붙잡을 수 없다. 사용자 대면 URL 로 때리는 점검은 DNS→CloudFront→ALB→파드→앱 전 구간을 통과하므로
타깃이 죽으면 503 으로 잡힌다.

⚠ 다만 **일부 파드만 죽은 상태**는 ALB 가 우회해 이 점검을 통과한다. 지금은 user·quiz 가 각
1 레플리카라 1대 실패 = 전면 장애여서 차이가 없지만, 상시 2대 이상으로 올리면 그때는 타깃그룹
지표가 따로 필요하다.

### 오탐·되감기 방어 (3중)

자동 롤백이 스스로 장애 원인이 되지 않게 하는 장치다.

1. **연속 3회 실패** (`datapoints_to_alarm`) — 단발 네트워크 흔들림에 되돌리지 않는다.
   `1` 로 낮추지 말 것. 변수 validation 이 막고 있다.
2. **알람은 상태 전이에만 통보한다** — ALARM 이 유지되는 동안 대응 Lambda 가 반복 호출되지 않는다.
3. **쿨다운** (`rollback_cooldown_seconds`, 기본 600초) — 알람이 OK↔ALARM 로 요동칠 때 계단식
   되감기를 막는다. `CURRENT` 마커의 최종 수정 시각으로 판정한다.

그리고 되돌릴 이전 릴리스가 없으면(최초 배포 등) 롤백하지 않고 알림만 보낸다.

### ⚠ `treat_missing_data` — 세 값 다 겪고 나서야 맞춘 곳

FE·BE 알람은 **`notBreaching`** 이다. 여기서 두 번 사고가 났으므로 바꾸기 전에 아래를 읽을 것.

| 값 | 실제 동작 | 결과 |
|---|---|---|
| `breaching` | 지표 없음 = 장애 | 최초 apply 직후, 첫 점검이 돌기 전에 알람이 `INSUFFICIENT_DATA → ALARM` 으로 튀어 **감시가 스스로 롤백했다** |
| `missing` | 없는 데이터를 **평가에서 제외** | `datapoints_to_alarm` 이 무의미해진다. 데이터가 드물면 **1회 실패로 발화** — 위 1번 방어가 사실상 없는 상태가 된다 |
| **`notBreaching`** | 없는 데이터를 정상으로 센다 | ALARM 에 닿으려면 **실제 실패가 그 개수만큼** 필요하다 |

`missing` 의 함정이 특히 위험하다. 겉보기엔 안전해 보이는데 **점검 Lambda 가 호출을 한 번만 거르면
단발 실패로 롤백한다.** 실제로 주기를 5분→1분으로 바꾼 직후 3개 구간 중 2개가 비어 있었고,
유일한 0 하나로 알람이 전이했다. CloudWatch 이력에 이렇게 남아 있다:

```
"1 out of the last 3 datapoints [0.0] was less than the threshold
 (minimum 3 datapoints for OK -> ALARM transition)"
```

3 개가 필요하다고 적힌 채 1 개로 전이한 것이다.

**감시가 멈춘 것은 `watchdog-stalled` 알람이 `breaching` 으로 따로 잡아 알림만 보낸다.**
그래야 '서비스 장애' 와 '감시 자신의 장애' 가 갈리고, 지표 공백이 롤백 트리거가 되지 않는다.

### CURRENT 마커

`s3://<버킷>/releases/CURRENT` 에 현재 서비스 중인 버전 문자열이 들어 있다. 배포 워크플로와
대응 Lambda 가 갱신한다.

⚠ **손으로 `aws s3 cp` 만 해서 되돌리면 마커가 낡아 거짓 정보가 된다.** 없는 것보다 나쁘다.
수동 롤백 시에는 마커도 함께 갱신할 것:

```bash
aws s3 cp s3://$B/releases/<버전>/index.html s3://$B/index.html
printf '%s' '<버전>' | aws s3 cp - s3://$B/releases/CURRENT
```

### 시크릿

코드에 두지 않고 SSM SecureString 이름만 참조한다. **파라미터가 없으면 그 기능만 생략되고 감시와
롤백은 계속 동작한다** — 토큰 하나 때문에 감시 전체가 멈추지 않게 하려는 것이다.

| 파라미터 | 용도 | 없으면 |
|---|---|---|
| `/victoryfairy/dev/slack-webhook-url` | Slack Incoming Webhook | 알림 생략 (롤백은 됨) |
| `/victoryfairy/dev/github-token` | 티켓 발행 (`issues:write` 만) | 티켓 생략 (롤백은 됨) |

## 주의할 것

**롤백은 코드가 아니라 배포본만 되돌린다.** git 은 그대로다. 원인을 고쳐 다시 배포하기 전까지는
`main` 과 서비스 중인 버전이 어긋난 상태다. 다음 배포가 돌면 그 버전으로 덮인다 — **고치지 않은 채
다른 변경을 배포하면 롤백이 조용히 취소된다.** 자동 롤백은 이 때문에 티켓을 함께 발행한다.
손으로 되돌렸다면 티켓도 손으로 남겨야 한다.

**BE 와 얽힌 문제라면 FE 롤백만으로 부족하다.** API 경로나 계약이 바뀌었다면 BE 쪽도 함께 되돌려야
한다. 실제로 2026-08-07 base path 교체(`/api/member`→`/api`) 때 옛 FE 번들이 옛 경로를 불러
화면은 뜨지만 로그인이 401 이 되는 상태를 겪었다.

**아카이브에 만료 규칙을 걸지 않는다.** `releases/` 에 "N 일 후 만료" 를 걸면 되돌릴 대상이
사라진다. 루트 자산도 같은 이유로 지우지 않는다 — 옛 `index.html` 이 참조하는 청크가 사라지면
그 버전으로는 되돌릴 수 없게 된다. 누적량은 배포당 수백 KB 수준이다.

정리해야 할 때는 나이로 판단하면 안 된다. `releases/*/index.html` 들을 훑어 **아직 참조되는 해시**를
모으고, 그 집합에 없는 루트 자산만 지운다.

## 검토했으나 막힌 길 — CloudFront KeyValueStore

버전 접두사를 URI 에 붙이고(`/releases/<버전>/…`) KVS 의 키 하나로 전환하는 방식을 구현까지 했다가
되돌렸다. **조직 SCP 가 `cloudfront-keyvaluestore` 네임스페이스를 명시적으로 거부한다.**

```
AccessDeniedException ... cloudfront-keyvaluestore:PutKey
with an explicit deny in a service control policy:
arn:aws:organizations::403164878212:policy/o-vfq3er10ky/service_control_policy/p-5soyo0ar
```

비대칭이 헷갈리기 쉬우니 적어 둔다. **컨트롤 플레인(`cloudfront:` — 스토어 생성·조회)은 허용되고
데이터 플레인(`cloudfront-keyvaluestore:` — 키 읽기·쓰기)만 거부된다.** 그래서 `terraform apply` 로
스토어는 만들어지고 콘솔에도 정상으로 보이는데, 값을 넣는 순간 거부된다.

다시 시도하기 전에 알아둘 것:

- **콘솔로도 안 된다.** SCP 는 인가 계층에서 평가되므로 콘솔·CLI·SDK 모두 동일하게 거부된다.
- **허용 SCP 를 추가해도 안 된다.** SCP 에서 명시적 Deny 는 어떤 Allow 로도 덮이지 않는다.
  거부하는 문장 자체를 고쳐야 한다.
- **관리 계정에서만 고칠 수 있다.** 이 계정(`555209622409`)은 멤버 계정이라
  `organizations:DescribeOrganization` 조차 거부된다. 관리 계정은 `403164878212`.
- `aws_cloudfront_key_value_store` 리소스에는 `ImportSource` 인자가 없고, 있더라도 import 는
  생성 시점 한 번뿐이라 배포마다 쓸 수 없다.

**SCP 가 완화되면** KVS 방식으로 승격할 수 있고, 그때도 이 `releases/` 아카이브를 그대로 쓴다
(달라지는 것은 전환 방법뿐이다). 그 방식의 이점은 롤백 속도가 아니라 — 이 문서의 방법도 수 초다 —
**자산을 접두사 단위로 통째로 정리할 수 있다**는 점이다. 지금은 루트에 영구 누적된다.
