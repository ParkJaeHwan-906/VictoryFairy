# 도메인 연결 + HTTPS 적용 runbook (victoryfairy.com)

EKS 위에서 루트 도메인 `victoryfairy.com` 을 ALB(Ingress)에 연결하고 HTTPS 를 적용하는 순서.
Terraform(`environments/dev`)이 Route53·ACM·IRSA 를 만들고, ALB 자체는 클러스터의
AWS Load Balancer Controller(LBC)가 Ingress 를 보고 만든다. ExternalDNS 가 A(ALIAS) 레코드를 자동 생성한다.

> 이미 갖춰진 전제(추가 작업 불필요): EKS 클러스터, IRSA용 OIDC provider,
> 퍼블릭 서브넷 태그 `kubernetes.io/role/elb=1`(modules/network).

관련 코드:
- `modules/dns` — Route53 존 + ACM 인증서(DNS 검증) + ExternalDNS IRSA
- `modules/alb` — AWS Load Balancer Controller IRSA(+공식 IAM 정책)
- `k8s/22-ingress.yaml` — ALB Ingress (host=victoryfairy.com)
- `k8s/23-external-dns.yaml` — ExternalDNS 배포

---

## 순서

모든 terraform 명령은 `environments/dev/` 에서 실행한다.

### 0. 초기화
새 모듈·provider 를 받는다.
```bash
cd environments/dev
terraform init
terraform plan   # dns·alb 모듈 신규 생성 리소스 확인
```

### 1. Route53 존 먼저 생성 → 네임서버 확보
ACM 검증(3단계)은 NS 가 레지스트라에 등록돼 있어야 통과하므로, **존만 먼저** 만든다.
```bash
terraform apply -target=module.dns.aws_route53_zone.this
terraform output route53_name_servers
```
출력된 **4개 NS 주소**(예: `ns-123.awsdns-45.com`, `ns-678.awsdns-90.net`,
`ns-901.awsdns-12.org`, `ns-234.awsdns-56.co.uk` 형태)를 **도메인을 구입한 곳(레지스트라)의
네임서버 설정**에 그대로 등록한다. 등록 후 전파까지 수 분~수 시간.
```bash
# 전파 확인 (4개 NS 가 보이면 완료)
dig NS victoryfairy.com +short
```

### 2. 이메일 DNS(Mailjet) 이관 — ⚠ 빠뜨리면 인증메일이 죽는다
`victoryfairy.com` 은 Mailjet 발신 도메인(`no-reply@victoryfairy.com`)이기도 하다.
네임서버를 Route53 로 옮겼으므로 Mailjet 의 **SPF/DKIM(TXT) 레코드를 Route53 존에 다시 넣어야**
이메일 인증 메일 도달률이 유지된다. Mailjet 콘솔의 도메인 인증 화면에 표시된 값으로:
```bash
# 예시 — 실제 값은 Mailjet 콘솔에서 확인
aws route53 change-resource-record-sets ...   # SPF(TXT) / DKIM(TXT) 레코드 추가
```
(또는 Route53 콘솔에서 TXT 레코드로 추가)

### 3. 전체 apply — ACM 검증 + IRSA 역할 생성
NS 전파가 끝난 뒤 실행한다. `aws_acm_certificate_validation` 이 검증 완료까지 대기한다.
```bash
terraform apply
```
> apply 가 `aws_acm_certificate_validation` 에서 오래 멈춘다면, 아직 NS 전파가 안 된 것.
> 1단계 `dig NS` 로 4개가 보이는지 확인 후 재시도.

산출 output:
```bash
terraform output route53_name_servers   # 레지스트라 등록용 NS
terraform output acm_certificate_arn     # (참고) LBC 가 host 로 자동 탐색
terraform output aws_lbc_role_arn        # LBC Helm 설치 SA 어노테이션
terraform output external_dns_role_arn   # k8s/23-external-dns.yaml SA 어노테이션과 일치해야 함
```

### 4. AWS Load Balancer Controller 설치 (Helm)
Terraform 은 IRSA 역할까지만 만든다. 컨트롤러 파드는 Helm 으로 설치한다.
```bash
aws eks update-kubeconfig --name victoryfairy-dev --region ap-northeast-2

helm repo add eks https://aws.github.io/eks-charts
helm repo update

helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName=victoryfairy-dev \
  --set region=ap-northeast-2 \
  --set serviceAccount.create=true \
  --set serviceAccount.name=aws-load-balancer-controller \
  --set serviceAccount.annotations."eks\.amazonaws\.com/role-arn"="$(terraform output -raw aws_lbc_role_arn)"

# 설치 확인 + IngressClass "alb" 생성 확인
kubectl -n kube-system rollout status deploy/aws-load-balancer-controller
kubectl get ingressclass
```
> LBC 이미지/차트 버전은 클러스터 k8s 버전(현재 1.30)과 호환되는 것을 쓸 것.
> IAM 정책(`modules/alb/iam_policy.json`)은 LBC v2.8.2 기준 — 차트 버전을 크게 바꾸면 정책도 갱신.

### 5. k8s 매니페스트 적용
```bash
kubectl apply -f k8s/00-namespaces.yaml
# (앱/설정 매니페스트: 10·20·21 등 기존 순서대로)
kubectl apply -f k8s/23-external-dns.yaml
kubectl apply -f k8s/22-ingress.yaml
```

### 6. 검증
```bash
# ALB 프로비저닝 확인 (ADDRESS 에 *.elb.amazonaws.com 가 뜬다)
kubectl -n victoryfairy get ingress victoryfairy -w

# ExternalDNS 가 A(ALIAS)/TXT 레코드를 만들었는지
kubectl -n kube-system logs deploy/external-dns | tail
dig victoryfairy.com +short          # ALB 주소로 해석되면 성공

# HTTPS 종단 확인
curl -I https://victoryfairy.com/api/auth   # TLS 핸드셰이크 + 응답
```

---

## ⚠ 알려진 블로커 / 주의

1. **헬스체크 503**: ALB 타깃그룹은 `healthcheck-path: /healthz`(22-ingress.yaml)에서 **200 을
   받아야** 타깃을 Healthy 로 본다. 현재 user/quiz 앱에 헬스 엔드포인트가 없으면 모든 타깃이
   Unhealthy → ALB 가 503 을 반환한다. **HTTPS 는 붙었는데 503 이면 이 문제다.**
   → 앱에 헬스 엔드포인트(예: `/healthz` 또는 actuator `/actuator/health`) 추가 필요(BE 작업).

2. **인증서 자동 탐색**: `22-ingress.yaml` 은 `certificate-arn` 을 생략해 LBC 가 host 로 ACM
   인증서를 자동 매칭한다. 여러 인증서가 같은 도메인을 커버하면 명시(`terraform output
   acm_certificate_arn`)하는 편이 안전하다.

3. **ExternalDNS 정책**: 기본 `--policy=upsert-only`(생성·갱신만, 삭제 안 함). Ingress 를
   지워도 DNS 레코드는 남는다. 완전 동기화가 필요하면 `--policy=sync` 로 바꾼다.

4. **클러스터 k8s 1.30 지원 종료**: 현재 클러스터는 1.30 으로, EKS 표준 지원이 끝나
   연장 지원(추가 과금) 구간이다. 도메인/HTTPS 작업과 별개로 버전 업그레이드를 계획할 것.
