# FE 정적 호스팅 (S3 + CloudFront)

FE 는 EKS 위의 nginx 파드가 아니라 S3 + CloudFront 가 서비스한다
(nginx 파드 `fe-app` 은 2026-08-07 제거됐다).

사용자가 보는 주소는 `victoryfairy.com` **하나다.** FE·API 가 같은 오리진이므로
CORS 도, FE 번들의 절대 URL 도 필요 없다.

---

## 1. 구성

```
브라우저 → victoryfairy.com(A→CloudFront) → CloudFront
                                             ├ /api/* ┐
                                             ├ /rt/*  ┴→ origin.victoryfairy.com(A→ALB)
                                             │           ├ /api → user-app
                                             │           └ /rt  → quiz-app
                                             ├ /assets/* → S3 (영구 캐시)
                                             ├ /user-profile-img/*, /temp/* → S3 asset 버킷
                                             ├ /characters/*, /items/*, /stores/* → S3 asset 버킷
                                             └ 그 외      → S3 (index.html, no-cache)
```

`/user-profile-img/*`·`/temp/*` 두 갈래는 사용자 업로드 asset 버킷 몫이다 —
FE 라우트를 정할 때 이 두 접두사를 피할 것(`docs/profile-image-apply.md` §8).

`/characters/*`·`/items/*`·`/stores/*` 는 **같은 버킷의 다른 성격**이다(캐릭터 꾸미기 에셋).
저자가 앱이 아니라 사람이고(`scripts/upload-character-assets.sh`), 앱은 이 접두사를 읽지도 쓰지도
않는다 — BE 는 DB 에 담긴 EP 문자열만 내보내고 실제 파일은 브라우저가 여기서 직접 받는다.
FE 라우트는 이 셋도 피해야 한다(총 다섯 접두사).

⚠ **버킷 정책과 behavior 는 짝이다.** 한쪽만 늘리면 증상이 갈린다 — behavior 만 있고 정책이 없으면
403, 정책만 있고 behavior 가 없으면 FE 버킷으로 흘러가 404 다. 단일 출처는
`environments/dev/locals.tf` 의 `asset_static_prefixes` 이고, DB 시드
(`VictoryFairy_BE/infra/sql/character-asset-init.sql`)에 박힌 EP 의 첫 세그먼트와도 일치해야 한다.

## 2. 왜 `origin.victoryfairy.com` 이 필요한가

CloudFront 는 오리진에 HTTPS 로 붙을 때 **오리진 도메인 이름과 오리진이 제시한 인증서가
일치하는지 검증한다.** ALB 의 실제 주소는 `k8s-*.ap-northeast-2.elb.amazonaws.com` 이고 ALB 에
달린 인증서는 `victoryfairy.com` 용이라 이름이 어긋나 연결이 거부된다.

그래서 ALB 에 인증서가 커버하는 이름을 붙인다. **이 이름은 CloudFront 가 뒤에서만 쓰므로
브라우저에 노출되지 않는다.**

> CloudFront→ALB 를 HTTP 로 붙이면 인증서 문제가 사라지지만, Bearer 토큰이 평문으로 인터넷을
> 건너간다. 택하지 않는다.

⚠ **이 이름은 apex 인증서의 SAN 이 아니라 전용 인증서다.** SAN 변경은 인증서를
'교체'하는데, 그 인증서는 운영 ALB 리스너가 물고 있다. `create_before_destroy` 로 새 인증서를
먼저 만들어도 옛 인증서 삭제에서 ACM 이 `ResourceInUseException` 을 낸다(리스너가 아직 참조 중).
두 인증서가 모두 apex 를 커버하는 동안 LBC 의 인증서 자동 탐색이 무엇을 고를지도 불확실하다.
별도 인증서면 **apex 인증서를 건드리지 않아 운영 TLS 가 무사하다.**
ALB 리스너는 SNI 로 여러 인증서를 붙일 수 있고, LBC 는 `victoryfairy-dns-origin` Ingress 의 host 를
보고 이 인증서를 자동 탐색한다.

**중요한 것은 이 이름이 TLS 검증에만 쓰인다는 점이다.** CloudFront 는 오리진 요청 정책
`Managed-AllViewer` 로 **Host 헤더는 브라우저가 보낸 값(`victoryfairy.com`)을 그대로 전달한다.**
SNI 와 Host 가 별개이므로 **ALB 규칙의 host 조건이 apex 그대로다** — CloudFront 경유와
브라우저 직접 접속이 같은 규칙을 쓴다.

### DNS 소유권

apex 를 두고 ExternalDNS 와 Terraform 이 다투지 않게 하는 것이 이 구성의 핵심이다.
ExternalDNS 가 user Ingress 의 `host: victoryfairy.com` 을 보고 apex A 레코드를 소유하면,
Terraform 이 apex 를 CloudFront 로 바꿔도 ExternalDNS 가 1 분 안에 ALB 로 되돌려 계속 싸운다.

그래서 **앱 Ingress 전부에 `external-dns.alpha.kubernetes.io/controller: none` 이 붙어 있다** —
ExternalDNS 가 apex 를 감시하지 않는다. 그러면 origin 레코드를 만들 주체가 없어지므로,
`origin.victoryfairy.com` 만 알리는 전용 Ingress(`victoryfairy-dns-origin`)를 하나 둔다.
Terraform 은 LBC 가 만든 ALB 의 주소를 알 수 없어 이 레코드를 대신 만들 수 없다.

| 이름 | 소유자 | 가리키는 곳 |
|------|--------|-------------|
| `victoryfairy.com` (apex) | Terraform (`modules/cdn`) | CloudFront |
| `origin.victoryfairy.com` | ExternalDNS (`victoryfairy-dns-origin` Ingress) | ALB |

`victoryfairy-dns-origin` 의 리스너 규칙은 평소 매칭되지 않는다(CloudFront 가 Host 를 apex 로
보내므로). 오리진 요청 정책을 `AllViewerExceptHostHeader` 로 바꾸는 날 그 규칙이 실제 경로가 된다.

## 3. CloudFront 구성에서 조심할 것

### 3.1 SPA fallback 은 custom error response 로 하지 않는다

`try_files $uri $uri/ /index.html` 을 CloudFront 로 옮길 때 흔한 방법이
custom error response(403/404 → `/index.html`, 200)인데, **이 설정은 behavior 단위가 아니라
distribution 전역이다.** 그러면 API 가 정직하게 내린 404·403 까지 `index.html` + 200 으로
바뀌어, FE 는 "성공했는데 JSON 이 아닌 HTML" 을 받는다.

그래서 **CloudFront Function(viewer request)** 을 S3 behavior 에만 붙여 경로를 다시 쓴다.
`/api/*`·`/rt/*` behavior 에는 함수가 붙지 않으므로 API 응답은 그대로 지나간다.
asset behavior 에도 붙이지 않는다 — 없는 이미지에 `index.html` 을 돌려주지 않기 위해서다.

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

캐시 정책은 Response Headers Policy 가 갖는다. `override = true` 라
S3 오브젝트에 헤더가 없거나 잘못 박혀 있어도 엣지가 덮어쓴다. 덕분에 배포 워크플로는
`aws s3 sync` 한 번으로 끝나고, 캐시 정책 조정은 FE 재배포 없이 `terraform apply` 만으로 된다.

| behavior | 브라우저 캐시 (Response Headers Policy) | 엣지 캐시 (Cache Policy) |
|---|---|---|
| `/assets/*` | `public, max-age=31536000, immutable` | 길게 — 파일명에 콘텐츠 해시가 있어 안전 |
| `/user-profile-img/*` | `public, max-age=31536000, immutable` | 길게 — 키가 UUID 라 덮어쓰기가 없다 |
| `/temp/*` | `public, max-age=300` | 짧게 — 하루면 사라지는 객체다 |
| `/characters/*`, `/items/*`, `/stores/*` | `public, max-age=86400` | 하루 — 키가 고정 슬러그라 그림 교체가 같은 키를 덮어쓴다 |
| 그 외 (index.html) | `no-cache` | 짧게 — 자산 해시가 적힌 곳이라 낡으면 배포가 먹히지 않는다 |

⚠ 캐릭터 에셋에 `immutable` 을 붙이지 않은 것이 요점이다. 붙이면 그림을 교체한 뒤 무효화를 돌려도
**이미 그 헤더를 받은 브라우저는 1년 동안 옛 그림을 계속 쓴다**(무효화는 엣지에만 닿는다). 이 셋은
SVG(텍스트)라 엣지 압축도 켜 뒀다 — 이미지 behavior 두 개가 압축을 끈 것과 반대다.

## 4. 배포 파이프라인

```
push → npm ci && npm run build → aws s3 sync → CloudFront invalidation
```

Docker 빌드·ECR·롤아웃이 없어 배포가 수십 초에 끝난다. OIDC 역할
(`victoryfairy-dev-github-actions`)이 갖는 것은 `s3:ListBucket`·`s3:GetObject`·`s3:PutObject` 와
`cloudfront:CreateInvalidation`/`GetInvalidation` 뿐이다.

⚠ **`s3:DeleteObject` 는 일부러 주지 않았다.** 배포는 `--delete` 없이 `sync` 하므로 필요가 없고,
없으면 CI 권한만으로는 사이트를 지울 수 없다. `--delete` 를 쓰면 캐시된 옛 `index.html` 을 들고
있는 브라우저가 사라진 청크를 요청해 화면이 깨진다 — 옛 자산 정리는 사람이 별도 권한으로 한다.

무효화 권한은 비상용이다. `index.html` 은 엣지 TTL 0 이라 배포·롤백에 무효화가 필요하지 않다.

롤백은 `kubectl rollout undo` 같은 한 방이 없다 — 절차는 [fe-release-rollback.md](fe-release-rollback.md).

## 5. 남은 항목

- **apex 의 ExternalDNS 소유권 TXT 레코드 정리 (미완).** ExternalDNS 는
  `--policy=upsert-only` 라 레코드를 지우지 않으므로 수동 정리가 필요하다.
  > 부수 효과: 이걸 치우면 apex TXT 충돌 때문에 보류해 둔 Mailjet SPF 레코드
  > (`environments/dev/main.tf` 의 `mailjet_spf_value`)를 등록할 수 있다.
