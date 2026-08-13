# FE 정적 호스팅 전환 (nginx 파드 → S3 + CloudFront)

FE 를 EKS 위의 nginx 파드(`k8s/24-fe-app.yaml`)에서 S3 + CloudFront 로 옮긴다.
같은 변경에서 API base path 도 정리한다 — `/api/member` → `/api`, `/api/game` → `/rt`.

사용자가 보는 주소는 `victoryfairy.com` **하나로 유지된다.** FE·API 가 계속 같은 오리진이므로
CORS 도, FE 번들의 절대 URL 도 필요 없다.

---

## 1. 전환 전 / 후

```
[전]  브라우저 → victoryfairy.com(A→ALB) → ALB
                                            ├ /api/member → user-app
                                            ├ /api/game   → quiz-app
                                            └ /           → fe-app (nginx 정적)

[후]  브라우저 → victoryfairy.com(A→CloudFront) → CloudFront
                                                   ├ /api/* ┐
                                                   ├ /rt/*  ┴→ origin.victoryfairy.com(A→ALB)
                                                   │           ├ /api → user-app
                                                   │           └ /rt  → quiz-app
                                                   ├ /assets/* → S3 (영구 캐시)
                                                   └ 그 외      → S3 (index.html, no-cache)
```

## 2. 왜 `origin.victoryfairy.com` 이 필요한가

CloudFront 는 오리진에 HTTPS 로 붙을 때 **오리진 도메인 이름과 오리진이 제시한 인증서가
일치하는지 검증한다.** ALB 의 실제 주소는 `k8s-*.ap-northeast-2.elb.amazonaws.com` 이고 ALB 에
달린 인증서는 `victoryfairy.com` 용이라 이름이 어긋나 연결이 거부된다.

그래서 ALB 에 인증서가 커버하는 이름을 붙인다. **이 이름은 CloudFront 가 뒤에서만 쓰므로
브라우저에 노출되지 않는다.**

> CloudFront→ALB 를 HTTP 로 붙이면 인증서 문제가 사라지지만, Bearer 토큰이 평문으로 인터넷을
> 건너간다. 택하지 않는다.

⚠ **이 이름을 apex 인증서의 SAN 으로 넣지 않고 전용 인증서를 따로 발급한다.** SAN 변경은 인증서를
'교체'하는데, 그 인증서는 지금 운영 ALB 리스너가 물고 있다. `create_before_destroy` 로 새 인증서를
먼저 만들어도 옛 인증서 삭제에서 ACM 이 `ResourceInUseException` 을 낸다(리스너가 아직 참조 중).
두 인증서가 모두 apex 를 커버하는 동안 LBC 의 인증서 자동 탐색이 무엇을 고를지도 불확실하다.
별도 인증서면 **apex 인증서를 건드리지 않아 운영 TLS 가 무사하고, plan 의 파괴가 0 건이 된다.**
ALB 리스너는 SNI 로 여러 인증서를 붙일 수 있고, LBC 는 `victoryfairy-dns-origin` Ingress 의 host 를
보고 이 인증서를 자동 탐색한다.

**중요한 것은 이 이름이 TLS 검증에만 쓰인다는 점이다.** CloudFront 는 오리진 요청 정책
`Managed-AllViewer` 로 **Host 헤더는 브라우저가 보낸 값(`victoryfairy.com`)을 그대로 전달한다.**
SNI 와 Host 가 별개이므로 **ALB 규칙의 host 조건을 바꿀 필요가 없다** — 기존 Ingress 가 그대로 맞고,
CloudFront 경유와 브라우저 직접 접속(롤백 경로)이 같은 규칙을 쓴다.

### DNS 소유권

apex 를 두고 ExternalDNS 와 Terraform 이 다투지 않게 하는 것이 전환의 핵심이다. 전환 전에는
ExternalDNS 가 user Ingress 의 `host: victoryfairy.com` 을 보고 apex A 레코드를 소유했다. 그 상태로
Terraform 이 apex 를 CloudFront 로 바꾸면 ExternalDNS 가 1 분 안에 ALB 로 되돌려 계속 싸운다.

그래서 **앱 Ingress 전부에 `external-dns.alpha.kubernetes.io/controller: none` 을 붙여** ExternalDNS
가 apex 를 더 이상 감시하지 않게 한다. 그러면 origin 레코드를 만들 주체가 없어지므로,
`origin.victoryfairy.com` 만 알리는 전용 Ingress(`victoryfairy-dns-origin`)를 하나 둔다.
Terraform 은 LBC 가 만든 ALB 의 주소를 알 수 없어 이 레코드를 대신 만들 수 없다.

| 이름 | 소유자 | 가리키는 곳 |
|------|--------|-------------|
| `victoryfairy.com` (apex) | Terraform (`modules/cdn`) | CloudFront |
| `origin.victoryfairy.com` | ExternalDNS (`victoryfairy-dns-origin` Ingress) | ALB |

`controller: none` 으로 바꿔도 **기존 apex 레코드는 지워지지 않는다** — ExternalDNS 가
`--policy=upsert-only` 이기 때문이다. 그래서 1 단계에서 트래픽이 변하지 않는다.

`victoryfairy-dns-origin` 의 리스너 규칙은 평소 매칭되지 않는다(CloudFront 가 Host 를 apex 로
보내므로). 오리진 요청 정책을 `AllViewerExceptHostHeader` 로 바꾸는 날 그 규칙이 실제 경로가 된다.

## 3. CloudFront 구성에서 조심할 것

### 3.1 SPA fallback 은 custom error response 로 하지 않는다

`nginx.conf` 의 `try_files $uri $uri/ /index.html` 을 CloudFront 로 옮길 때 흔한 방법이
custom error response(403/404 → `/index.html`, 200)인데, **이 설정은 behavior 단위가 아니라
distribution 전역이다.** 그러면 API 가 정직하게 내린 404·403 까지 `index.html` + 200 으로
바뀌어, FE 는 "성공했는데 JSON 이 아닌 HTML" 을 받는다.

그래서 **CloudFront Function(viewer request)** 을 S3 behavior 에만 붙여 경로를 다시 쓴다.
`/api/*`·`/rt/*` behavior 에는 함수가 붙지 않으므로 API 응답은 그대로 지나간다.

### 3.2 허용 메서드를 7 개 전부 열어야 한다

CloudFront behavior 의 기본 허용 메서드는 `GET, HEAD` 다. `/api/*`·`/rt/*` 에서 이대로 두면
로그인·회원가입 등 **모든 POST 가 405 로 막힌다.** `GET HEAD OPTIONS PUT POST PATCH DELETE`
전부 허용해야 한다.

### 3.3 API behavior 는 캐싱을 끄고 Authorization 을 넘겨야 한다

- 캐시 정책 `Managed-CachingDisabled` — API 응답을 엣지가 캐시하면 다른 사용자에게 샌다.
- 오리진 요청 정책 `Managed-AllViewer` — `Authorization` 헤더·쿼리·쿠키·Host 를 모두 오리진에
  전달한다. §2 에서 설명한 대로 Host 를 그대로 넘겨야 기존 Ingress 규칙이 맞는다.
  `AllViewerExceptHostHeader` 로 바꾸면 Host 가 `origin.victoryfairy.com` 이 되어 규칙과 어긋나
  파드까지 못 가고 404 가 된다 — 그 정책을 쓰려면 Ingress host 도 함께 내려야 한다.

또 압축을 끈다(`compress = false`). 채팅 SSE(`text/event-stream`)가 엣지에서 버퍼링될 여지를
만들지 않는 쪽을 택했다 — JSON 압축 이득보다 이미 도는 실시간 스트림을 흔들지 않는 것이 우선이다.

### 3.4 SSE(채팅)는 그대로 동작한다

CloudFront 의 오리진 응답 타임아웃은 기본 30 초이고, **패킷 사이 유휴 시간에도 적용된다.**
`SseEmitterRegistry.heartbeat()` 이 `@Scheduled(fixedRate = 15_000L)` 로 15 초마다 `:ping`
주석 프레임을 보내므로 이 한계에 걸리지 않는다.

⚠ 하트비트 주기를 30 초 이상으로 늘리면 CloudFront 가 스트림을 끊는다. 주기를 바꿀 때는
오리진 타임아웃도 함께 올려야 한다.

### 3.5 캐시 헤더는 인프라가 소유한다

`nginx.conf` 가 하던 캐시 정책을 Response Headers Policy 로 옮긴다. `override = true` 라
S3 오브젝트에 헤더가 없거나 잘못 박혀 있어도 엣지가 덮어쓴다. 덕분에 배포 워크플로는
`aws s3 sync` 한 번으로 끝나고, 캐시 정책 조정은 FE 재배포 없이 `terraform apply` 만으로 된다.

| behavior | 브라우저 캐시 (Response Headers Policy) | 엣지 캐시 (Cache Policy) |
|---|---|---|
| `/assets/*` | `public, max-age=31536000, immutable` | 길게 — 파일명에 콘텐츠 해시가 있어 안전 |
| 그 외 (index.html) | `no-cache` | 짧게 — 자산 해시가 적힌 곳이라 낡으면 배포가 먹히지 않는다 |

## 4. 무중단 전환 순서

각 단계는 되돌릴 수 있고, 실제 트래픽이 움직이는 것은 **2 단계 하나뿐이다.**

### 1 단계 — 준비 (트래픽 변화 없음)

1. **BE 배포** — 새 context-path(`/api`, `/rt`)를 담은 이미지를 롤아웃한다. 이 시점부터 옛
   Ingress 규칙(`/api/member`, `/api/game`)이 맞지 않아 API 가 끊긴다 → 곧바로 2 번을 적용한다.
2. **앱 Ingress 3 개만** 적용한다 — path 를 `/api`·`/rt` 로, 헬스체크 경로를
   `/api/actuator/health/readiness`·`/rt/actuator/health/readiness` 로 교체하고 `controller: none`
   을 붙인다. 여기서 순단이 끝난다.
   `curl https://victoryfairy.com/api/actuator/health/readiness` 가 200 인지 확인한다.

   ⚠ **`victoryfairy-dns-origin` 은 이 단계에서 적용하지 말 것.** LBC 는 그룹의 모든 Ingress
   host 에 맞는 ACM 인증서를 찾는데, origin 인증서는 3 번에서 생긴다. 없는 상태로 올리면
   인증서 탐색이 실패해 **ALB 그룹 전체 조정이 깨질 수 있다**(API 까지 함께 죽는다).
   그래서 4 번으로 분리한다. 부분 적용은 그 문서만 골라내 apply 하면 된다:
   ```bash
   python -c "import yaml;docs=[d for d in yaml.safe_load_all(open('VictoryFairy_Infra/k8s/22-ingress.yaml',encoding='utf-8')) if d and d['metadata']['name']!='victoryfairy-dns-origin'];yaml.safe_dump_all(docs,open('/tmp/ing.yaml','w',encoding='utf-8'),allow_unicode=True,sort_keys=False)"
   kubectl -n victoryfairy apply -f /tmp/ing.yaml
   ```
3. `terraform apply` — 인증서 2 장(CloudFront 용 us-east-1, 오리진용 서울), S3 버킷,
   CloudFront distribution 생성. `fe_attach_apex_alias` 는 **`false` 그대로 둔다** —
   apex 는 아직 ALB 를 가리킨다.
   **plan 은 전부 신규 생성이어야 한다(파괴 0 건).** 파괴가 잡히면 멈추고 원인을 볼 것.
4. `kubectl apply -f k8s/22-ingress.yaml` — 이제 전체를 적용해 `victoryfairy-dns-origin` 을
   추가한다. ExternalDNS 가 `origin.victoryfairy.com` A 레코드를 만들고, LBC 가 3 번에서 생긴
   origin 인증서를 리스너에 붙인다.
   붙었는지 확인한다 — 안 붙었으면 Ingress 를 건드려 재조정시킨다:
   ```bash
   aws elbv2 describe-listener-certificates --listener-arn <https-listener>
   ```
   `origin.victoryfairy.com` 이 ALB 로 해석되는지도 확인한다(`dig`/`nslookup`).
   ⚠ 이것이 안 되면 CloudFront 가 오리진에 닿지 못해 `/api/*` 가 전부 502 가 된다.
5. FE 를 S3 에 올리고(`deploy-fe.yml` 실행) CloudFront 를 거쳐 검증한다.
   딥링크 새로고침, `/api` 로그인, `/rt` 채팅 SSE 를 모두 확인한다.

   ⚠ **배포 도메인(`d*.cloudfront.net`)으로 그냥 접속하면 `/api/*`·`/rt/*` 검증이 안 된다.**
   오리진 요청 정책이 `Managed-AllViewer` 라 Host 헤더가 브라우저가 보낸 값 그대로 전달되는데,
   그 값이 `d*.cloudfront.net` 이면 ALB 규칙의 host 조건(`victoryfairy.com`)과 어긋나 404 가 된다.
   정적 자산만 확인되고 API 는 확인되지 않는다.

   Host 를 apex 로 보내면서 CloudFront 로 붙어야 전환 후와 동일한 경로를 미리 검증할 수 있다:
   ```bash
   EDGE=$(nslookup <배포도메인> 8.8.8.8 | tail -2 | head -1 | awk '{print $NF}')
   curl -s -o /dev/null -w "%{http_code}\n" \
     --resolve victoryfairy.com:443:$EDGE https://victoryfairy.com/api/actuator/health/readiness
   ```
   브라우저로 눈으로 보려면 hosts 파일에 `<엣지IP> victoryfairy.com` 을 임시로 넣는다.
   ⚠ 이 단계까지 사용자 트래픽은 ALB→fe-app(옛 번들)로 간다. 옛 번들은 `/api/member` 를 부르고
   그 경로는 이제 `/api` 규칙에 걸려 401 이 되므로 **1 번부터 이 시점까지 실서비스 로그인이
   불가하다.** 1~6 을 한 세션에 이어서 끝낼 것.
   순단을 줄이려면 1 번 전에 옛 워크플로의 base URL 만 `/api`·`/rt` 로 고쳐 fe-app 에 새 번들을
   먼저 배포하는 방법이 있다. 그러면 6 번 롤백 시에도 FE 가 온전히 동작한다(아래 롤백 항목 참고).

### 2 단계 — 전환 (유일한 위험 구간)

6. `fe_attach_apex_alias = true` 로 `terraform apply` — apex A/AAAA(ALIAS)를 CloudFront 로 교체
   (`allow_overwrite = true` 로 ExternalDNS 가 남긴 레코드의 소유권을 가져온다).
   DNS TTL(60 초)이 지나면 트래픽이 CloudFront 로 넘어간다.

**롤백**: apex 를 다시 ALB 로 되돌린다. `fe_attach_apex_alias = false` 로 apply 하면 Terraform 이
레코드를 **삭제**하므로 도메인이 어디도 가리키지 않는다 — 대신 아래 중 하나를 쓴다.
- (권장) 콘솔/CLI 로 apex A 를 ALB ALIAS 로 수동 UPSERT 한 뒤, 여유가 생기면 코드를 정리한다.
- 또는 앱 Ingress 의 `controller: none` 을 걷어내 ExternalDNS 가 apex 를 되찾게 한다(1 분 내 복구).

⚠ **3 단계(정리)를 마친 뒤에는 이 롤백이 FE 를 되살리지 못한다.** ALB 에 '/' 규칙과 fe-app 이
없으므로 apex 를 ALB 로 돌리면 정적 파일을 줄 주체가 사라진다. 그때의 복구 수단은 apex 를
CloudFront 로 되돌리는 것뿐이다(= 앞으로 고치기).

애초에 3 단계 전이라도 이 롤백은 반쪽이었다. fe-app 에 박힌 이미지는 전환 직전 버전에서 멈춰
있어 옛 API 경로(`/api/member`)를 부르고, 그 경로는 이제 `/api` 규칙에 걸려 401 이 된다 — 화면은
떠도 로그인이 안 된다. 온전한 롤백을 원한다면 전환 전에 fe-app 에 새 번들을 한 번 배포해 둬야
한다(1 단계 5 번 참고).

### 3 단계 — 정리 ✅ 2026-08-07 완료

9. apex 에 남은 ExternalDNS 소유권 TXT 레코드 정리. **(미완)**
   ExternalDNS 는 `--policy=upsert-only` 라 레코드를 지우지 않으므로 수동 정리가 필요하다.
   > 부수 효과: 이걸 치우면 apex TXT 충돌 때문에 보류해 둔 Mailjet SPF 레코드
   > (`environments/dev/main.tf` 의 `mailjet_spf_value`)를 등록할 수 있다.
## 5. API base path 교체의 순단

`/api/member` → `/api`, `/api/game` → `/rt` 는 세 곳이 동시에 맞아야 한다.

| 위치 | 변경 | 브랜치 |
|---|---|---|
| `user/application.yaml` `context-path` | `/api/member` → `/api` | dev_be |
| `quiz/application.yaml` `context-path` | `/api/game` → `/rt` | dev_be |
| `22-ingress.yaml` path + 헬스체크 경로 | `/api`, `/rt` | dev_infra |
| CloudFront behavior | `/api/*`, `/rt/*` | dev_infra |
| `deploy-fe.yml` `VITE_API_*_BASE_URL` | `/api`, `/rt` | dev_infra |
| `src/api/config.ts` 로컬 폴백 | `/api`, `/rt` | dev_fe |

⚠ **BE 배포와 Ingress 적용 사이에 수십 초 API 공백이 생긴다.** ALB 헬스체크 경로가
`/api/member/actuator/health/readiness` → `/api/actuator/health/readiness` 로 함께 바뀌는데,
한 앱이 두 context-path 를 동시에 서비스할 수 없어 겹치는 구간을 만들 수 없다.

순서: **BE 배포 → Ingress 적용 → 타깃 Healthy 확인 → FE 재배포.**
BE 를 먼저 올려야 새 Ingress 의 헬스체크가 통과한다. 뒤집으면 타깃이 전부 Unhealthy 가 되어
공백이 수십 초에서 수 분으로 늘어난다.

## 6. 배포 파이프라인 변화

```
[전] push → docker build(Vite) → ECR push(SHA) → kubectl set image → rollout → 실패시 undo
[후] push → npm ci && npm run build → aws s3 sync → CloudFront invalidation
```

Docker 빌드·ECR·롤아웃이 사라져 배포가 수 분에서 수십 초로 준다. OIDC 역할
(`victoryfairy-dev-github-actions`)에 `s3:PutObject`/`s3:DeleteObject`/`s3:ListBucket` 과
`cloudfront:CreateInvalidation` 을 추가한다.

⚠ **`--delete` 로 옛 자산을 즉시 지우면 안 된다.** 캐시된 옛 `index.html` 을 들고 있는
브라우저가 사라진 청크를 요청해 화면이 깨진다. `sync` 는 `--delete` 없이 돌리고, 옛 자산은
S3 lifecycle 로 유예 후 정리한다.

⚠ **롤백 방식이 달라진다.** `kubectl rollout undo` 같은 한 방이 없다. 즉시 롤백이 필요하면
이전 커밋을 재배포한다(빌드가 수십 초라 실용적이다). 버킷 버저닝은 켜 두어 최후의 수단을 남긴다.
