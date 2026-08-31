# 랜딩 페이지 (Vercel)

랜딩 페이지는 이 레포가 배포하지 않는다. Vercel 이 빌드·호스팅·TLS·롤백을 전부 갖고,
인프라가 소유한 것은 **Route53 레코드 두 개뿐이다.**

```
브라우저 → landing.victoryfairy.com  (CNAME) → Vercel
         → victoryfairy.com          (A ALIAS) → CloudFront ─┬→ S3 (FE·에셋)
                                                             └→ ALB (/api, /rt)
```

apex 쪽 구성은 이 문서와 무관하다(→ [fe-hosting.md](fe-hosting.md)). 두 사이트는 오리진도
인증서도 배포 경로도 겹치지 않는다.

---

## 1. 왜 CloudFront 를 하나 더 세우지 않았나

기존 FE 처럼 S3+CloudFront 로 갈 수도 있었다. 그러려면 버킷·us-east-1 인증서·배포판·
Route53 ALIAS·CI OIDC 권한·배포 워크플로를 새로 만들어야 하고, `modules/cdn` 은 API behavior 와
에셋 접두사와 apex ALIAS 가 한 덩어리라 **재사용이 아니라 슬림 모듈을 새로 쓰는 일**이 된다.

그 값을 치르고 얻는 게 없다는 것이 판단의 핵심이다. 랜딩 페이지는 운영 서비스와 결합도가 0이다 —
IRSA·SG·ALB 와 물릴 것이 없고, 트래픽도 CloudFront 무료 티어 안쪽이라 비용 차이도 없다.
반대로 Vercel 은 커밋별 프리뷰와 즉시 롤백을 그냥 준다. 랜딩 페이지처럼 카피와 디자인을 자주
갈아엎는 대상에서 가장 값어치 있는 게 그 둘이다.

> ⚠ Vercel Hobby 플랜은 비상업 용도로 제한된다. 서비스에 수익화 요소가 붙는 시점에는
> Pro 로 올리거나 위 CloudFront 안으로 되돌리는 판단이 다시 필요하다.

## 2. 왜 서브도메인인가 — `victoryfairy.com/landing` 을 기각한 이유

같은 오리진 아래 경로로 두는 안은 **운영 CloudFront 배포판을 고치는 일**이라 기각했다.
세 가지가 걸린다.

**Host 헤더가 `/api/*` 와 반대여야 한다.** API behavior 는 `Managed-AllViewer` 로 브라우저의
Host(apex)를 그대로 넘긴다. 같은 정책을 Vercel 오리진에 쓰면 Vercel 이 모르는 도메인이라
`DEPLOYMENT_NOT_FOUND` 를 낸다. `AllViewerExceptHostHeader` 로 Host 를 `*.vercel.app` 으로
바꿔 보내야 한다 — 한 배포판 안에서 정책이 갈린다.

**랜딩 앱을 basePath `/landing` 으로 빌드해야 한다.** CloudFront 도 ALB 도 경로를 rewrite 하지
않는다. basePath 없이 빌드하면 `/_next/*` 같은 자산 요청이 기본 behavior 로 흘러 S3 FE 버킷에
닿고, 거기 붙은 SPA fallback 함수가 **JS 를 요청한 자리에 FE 의 `index.html` 을 200 으로**
돌려준다. `/assets/*` 는 FE 번들이 이미 점유한 경로라 아예 충돌한다.

**예약 접두사가 늘어난다.** FE 라우트가 피해야 하는 접두사가 다섯에서 여섯이 되고,
버킷 정책 ↔ behavior 짝 맞추기 규약에 항목이 하나 더 붙는다(fe-hosting.md §1).

SEO 를 근거로 같은 오리진을 원한다면, 값이 모이는 자리는 `/landing` 이 아니라 루트다.
그 목표라면 정공법은 **apex 를 랜딩으로 삼고 앱을 `app.<domain>` 으로 옮기는** 구성이고,
그건 FE 호스팅 이전 규모의 별도 작업이다.

## 3. Route53 에 있는 것

`environments/dev/main.tf` 의 `aws_route53_record.landing` / `.landing_vercel_verify`.

| 이름 | 타입 | 값 | 용도 |
|------|------|-----|------|
| `landing.<domain>` | CNAME | `*.vercel-dns-017.com` | Vercel 프로젝트로 라우팅 |
| `_vercel.<domain>` | TXT | `vc-domain-verify=...` | 도메인 소유권 검증 |

CNAME 타깃은 **Vercel 프로젝트별로 발급된다** — 공용 `cname.vercel-dns.com` 이 아니므로
다른 프로젝트의 값을 복사해 오면 안 된다.

`_vercel` TXT 는 존 단위 검증이라 같은 Vercel 계정에서 서브도메인을 더 붙일 때 재사용된다.
다른 계정에서 추가하면 값이 달라 충돌한다.

## 4. 왜 네임서버를 Vercel 로 옮기지 않았나

Vercel 은 도메인 추가 시 네임서버 이관을 기본으로 권한다. **택하지 않았다.** 존이 Route53 을
떠나면 ACM DNS 검증 CNAME 2장(서울·us-east-1), `origin.<domain>` A/TXT, Mailjet DKIM·검증
TXT 가 함께 사라진다. 증상이 즉시 드러나지 않고 **인증서 갱신 시점에 터진다**는 게 특히 나쁘다.

같은 이유로, 도메인 추가 화면에서 **apex 를 함께 등록하자는 제안도 거절해야 한다.** 수락하면
Vercel 이 apex 용 A 레코드를 요구하는데, apex 는 CloudFront ALIAS 로 FE·API 를 서비스하는
자리다 — 갈아끼우는 순간 서비스가 내려간다.

## 5. ExternalDNS 와 겹치지 않는 이유

apex 를 두고 ExternalDNS 와 Terraform 이 다투는 문제(fe-hosting.md §2)는 여기서 재현되지 않는다.
ExternalDNS 는 `--source=ingress` 라 **Ingress host 로 선언된 이름만** 보고, `--policy=upsert-only`
라 남의 레코드를 지우지도 않는다(`k8s/23-external-dns.yaml`). `landing.<domain>` 은 어느
Ingress 에도 없다.

`_vercel` TXT 도 이름이 apex 가 아니라서, ExternalDNS 소유권 TXT 때문에 보류 중인 Mailjet SPF
문제와 무관하다(`modules/dns/main.tf` §4).

## 6. 인프라가 책임지지 않는 것

TLS 인증서는 Vercel 이 자체 발급한다 — **ACM 인증서에 SAN 을 추가하지 않았다.** apex 인증서를
건드리면 인증서가 '교체'되고 운영 ALB 리스너가 그것을 물고 있어 삭제가 거부된다
(`modules/dns/main.tf` §2-1). 랜딩을 위해 그 위험을 질 이유가 없다.

배포·롤백·프리뷰도 전부 Vercel 대시보드 소관이다. **랜딩이 죽어도 `terraform plan` 에는 볼 것이
없다** — 이 레포가 아는 것은 "이 이름이 Vercel 을 가리킨다" 뿐이다.

Production Branch 설정은 Vercel 쪽에 있다. 이 저장소 규약대로 `dev_*` → `main` 으로 배포한다면
`main` 이어야 한다. 모노레포이므로 Root Directory 를 랜딩 디렉터리로 지정해 두지 않으면
BE·인프라 커밋마다 Vercel 빌드가 돈다.

## 7. 바꿀 때

- **서브도메인 이름 변경**: Vercel 에서 새 이름을 추가해 CNAME 타깃을 받고, 레코드를 바꾼 뒤
  옛 이름을 Vercel 에서 제거한다. 순서를 뒤집으면 그 사이 502 가 뜬다.
- **Vercel 프로젝트 교체**: CNAME 타깃이 프로젝트별이라 함께 바뀐다. TXT 는 계정이 같으면 그대로다.
- **CloudFront 로 되돌리기**: DNS 를 바꾸기 전에 배포판·인증서를 먼저 만들어 두면 전환이
  레코드 한 줄이다. Vercel 쪽 도메인은 마지막에 뗀다.
