# 프로필 이미지 인프라 apply 절차 (타깃 apply)

브랜치 `hwannee/infra/feat-profile-img` 가 추가한 **사용자 업로드 자산 스택**(S3 asset 버킷 +
CloudFront 오리진·behavior + user-app IRSA)을 **어떻게 내보내는가**만 다루는 문서다.
설계와 근거는 코드 주석(`modules/asset`, `modules/cdn`, `modules/user-irsa`)에 있다.

> **한 줄 요약**: 그냥 `terraform apply` 하지 말고 `-target` 으로 세 모듈만 내보낸다.
> 이 브랜치와 무관한 **fe-watchdog 드리프트가 같은 plan 에 딸려 오기 때문**이다.

---

## 1. 왜 통짜 apply 를 하지 않는가

이 브랜치에서 plan 을 내면 **우리가 만들지 않은 변경 2건**이 함께 뜬다.

```
~ module.fe_watchdog.aws_lambda_function.healthcheck   source_code_hash
~ module.fe_watchdog.aws_lambda_function.responder     source_code_hash
```

- 정체: 2026-08-14 커밋 `3bacddc`("진입 번들을 파일명이 아니라 모듈 스크립트 src 로 찾는다")가
  **커밋만 되고 apply 되지 않은 기존 드리프트**다. `.py` 가 바뀌었으니
  `data.archive_file` 의 해시가 바뀌고, 그것이 그대로 계획에 남아 있다.
- 그대로 apply 하면 **FE 자동 롤백 워치독의 코드가 프로필 이미지 배포에 묻어 나간다.**
  워치독은 배포 직후 스모크 실패를 잡아 되돌리는 장치라, 그게 오작동하면 다음 FE 배포에서
  드러난다 — 그때 원인 후보가 "프로필 이미지 apply" 와 "워치독 코드" 둘이 된다.
- **코드로 드리프트를 없애려 하지 말 것.** `.py` 를 되돌리는 것은 잘못된 해법이다 —
  그 커밋은 그 자체로 옳고, 다만 **검증할 수 있는 시점에 따로 나가야** 한다. §4 참조.

## 2. 이번에 내보낼 것

| 모듈 | 무엇이 생기나 |
|---|---|
| `module.asset` | asset 버킷 + SSE + 퍼블릭 차단 + 라이프사이클 + 버킷 정책 |
| `module.cdn` | asset OAC 1 + 캐시 정책 2 + 응답 헤더 정책 2, 배포에 오리진·behavior **in-place** 추가 |
| `module.user_irsa` | user-app 파드용 IAM 역할 + 인라인 정책 |

```bash
cd VictoryFairy_Infra/environments/dev

# ⚠ terraform.tfvars 가 있어야 한다. 없으면 변수 기본값으로 계획이 잡혀
#   이 브랜치와 무관한 리소스가 destroy 로 나온다(운영 DB 포함). 없으면 먼저 채울 것.
terraform init

terraform plan \
  -target=module.asset \
  -target=module.cdn \
  -target=module.user_irsa \
  -out=profile-img.tfplan

terraform apply profile-img.tfplan
```

**plan 에서 확인할 것**

- **`12 to add, 2 to change, 0 to destroy`** (2026-08-20 실측). destroy 가 하나라도 있으면 멈춘다.
- change 2건의 정체는 **CloudFront 배포**와 **`module.cdn.aws_s3_bucket_policy.fe`** 다.
  뒤엣것이 왜 뜨는지는 §3 — 놀랄 일이 아니지만 내용은 확인해야 한다.
- CloudFront 배포는 **in-place 갱신**(`~`)이어야 한다. `-/+` 면 배포가 교체되는 것이고,
  그러면 도메인·인증서 배선이 통째로 흔들린다.
- `module.fe_watchdog` 항목이 **하나도 없어야 한다.** 있으면 `-target` 이 빠진 것이다.

⚠ **검증용 plan 파일을 그대로 apply 하지 말 것.** 리뷰·검증 단계의 plan 은 보통 `-lock=false`
로 뽑는다(다른 사람의 apply 를 막지 않으려고). 그 파일에는 락이 없으므로, apply 직전에
**락을 건 상태로 plan 을 새로 뽑아** 그 파일을 적용한다.

## 3. 왜 `module.cdn` 을 통째로 지정하는가

이 apply 에서 cdn 이 실제로 해야 할 일은 OAC 1 + 정책 4개 생성과 배포의 in-place 갱신뿐이다.
그런데 `-target=module.cdn` 으로 모듈을 통째로 넣으면 **`aws_s3_bucket_policy.fe`(FE 버킷 정책)가
change 로 함께 뜬다.** 두 가지를 구분해서 알아둘 것.

### 왜 no-op 이 아니라 change 로 보이나

- 그 정책 본문은 `data.aws_iam_policy_document.fe_bucket` 이고, 이 data 는
  `aws_cloudfront_distribution.this.arn` 을 `AWS:SourceArn` 조건에 쓴다.
- 이번 apply 가 그 배포를 갱신하므로 **data 읽기가 apply 시점으로 미뤄진다** → 정책 JSON 이
  `known after apply` 가 되고, 값이 같을 것이 뻔해도 Terraform 은 그것을 미리 알 수 없어
  change 로 계획한다.
- 실질 영향: 배포 ARN 은 바뀌지 않으므로 **같은 정책이 같은 값으로 다시 쓰일 뿐**이고
  FE 서비스 동작은 변하지 않는다. 그래도 apply 후 §5 절차대로 본문을 대조할 것.

### 뺄 수 있지만, 빼지 않는다

**"의존 때문에 어차피 끌려온다"는 말은 사실이 아니다.** `-target` 은 대상의 *의존 대상
(dependencies)* 만 함께 끌어오고 *의존하는 쪽(dependents)* 은 끌어오지 않는다. fe 버킷 정책은
배포의 dependent 라서, 리소스 단위로 좁히면 **깨끗하게 빠진다.** 실측(2026-08-20):

```bash
terraform plan -target=module.asset -target=module.user_irsa   -target=module.cdn.aws_cloudfront_distribution.this   -target=module.cdn.aws_cloudfront_origin_access_control.asset   -target=module.cdn.aws_cloudfront_cache_policy.asset   -target=module.cdn.aws_cloudfront_cache_policy.asset_temp   -target=module.cdn.aws_cloudfront_response_headers_policy.asset   -target=module.cdn.aws_cloudfront_response_headers_policy.asset_temp
# → Plan: 12 to add, 1 to change, 0 to destroy.   (aws_s3_bucket_policy.fe 없음)
```

그런데도 §2 는 모듈 단위(`-target=module.cdn`)를 권한다. 이유는 "뺄 수 없어서"가 아니라
**일부러 그렇게 하는 것**이다.

- **부분 state 를 덜 만든다.** 리소스 단위 target 은 같은 모듈 안에 '적용된 리소스'와
  '계획만 남은 리소스'를 섞어 놓는다. 모듈 경계로 자르면 그 경계가 곧 "여기까지는 최신"이라는
  선이 되어, 다음 사람이 무엇이 밀렸는지 판단하기 쉽다.
- **리뷰가 정직해진다.** 리소스를 골라 담으면 계획에서 change 1건이 사라지는데, 그건 그 변경이
  없어진 게 아니라 **다음 apply 로 미뤄진 것**이다. 어차피 무해한 재작성이라면 지금 눈앞에
  띄워 놓고 확인하는 편이 낫다.
- **타이핑 실수 면적이 작다.** 위 7줄짜리 target 목록은 하나만 빠뜨려도 조용히 반쪽 apply 가 된다.

→ 급해서 fe 쪽을 정말 건드리고 싶지 않은 상황이라면 위 리소스 단위 조합이 유효한 선택지다.
   그 경우 `aws_s3_bucket_policy.fe` 의 갱신은 **다음 통짜 apply 때 뜬다**는 것만 기억할 것.

### 세 모듈을 한 apply 에 넣어야 하는 이유

`module.asset` 은 `module.cdn.distribution_arn` 을(버킷 정책의 SourceArn),
`module.cdn` 은 `module.asset.bucket_regional_domain_name` 을 받는다. 그래프는 리소스 단위라
순환은 아니지만(버킷 → 오리진 → 배포 → 버킷 정책), **한쪽만 target 하면 반쪽만 만들어진다** —
버킷은 섰는데 배포가 그것을 오리진으로 갖지 않거나, 그 반대다. 세 개를 한 번에 지정한다.

### `-target` 경고에 대해

Terraform 이 "Resource targeting is in effect … not recommended" 를 찍는다. 정상이다.
다만 타깃 apply 직후의 state 는 **부분적으로만 최신**이다 — §4 가 남아 있다는 뜻이니
다음 apply 를 오래 미루지 말 것.

## 4. 남아 있는 별건 — fe-watchdog 드리프트 (열린 항목)

**이 스택을 내보낸 뒤에도 워치독 드리프트는 그대로 남는다.** 잊지 말 것.

- 대상: `module.fe_watchdog.aws_lambda_function.healthcheck` · `.responder` 의 `source_code_hash`
- 원인: 커밋 `3bacddc`(2026-08-14) — 코드는 머지됐고 apply 가 안 됐다.
- 조치: **FE 배포/롤백 동작을 바로 확인할 수 있는 시점에** 따로 내보낸다.

  ```bash
  terraform plan -target=module.fe_watchdog -out=watchdog.tfplan
  terraform apply watchdog.tfplan
  ```

  적용 후 확인 지점은 [fe-release-rollback.md](fe-release-rollback.md) §자동 롤백.
- ⚠ 이 문서를 지우기 전에 이 항목이 닫혔는지 확인할 것.

## 5. apply 직후 확인

```bash
# fe 버킷 정책이 '같은 값으로 다시 쓰인 것' 인지 대조한다(§3).
aws s3api get-bucket-policy --bucket victoryfairy-dev-fe --query Policy --output text | jq .
```

- 배포 ARN 이 바뀌지 않았으므로 **본문은 apply 전과 동일해야 정상**이다.
  달라졌다면 뭔가 잘못된 것이니(배포가 교체됐거나 다른 변경이 섞였거나) 멈추고 원인을 볼 것.
- 이미지 경로도 한 번 친다 — `curl -I https://victoryfairy.com/user-profile-img/<키>` 가
  S3 오리진에서 200 으로 오는지, `x-cache` 가 CloudFront 응답인지.

## 6. apply 뒤에 이어지는 작업 (Terraform 밖)

1. **k8s 매니페스트** — `k8s/20-user-app.yaml` 에 ServiceAccount(`victoryfairy/user-app`)를 만들고
   `eks.amazonaws.com/role-arn` 에 출력값 `user_app_role_arn` 을 넣는다. Deployment 에
   `serviceAccountName: user-app` 이 없으면 IRSA 권한이 파드에 닿지 않는다.
   ```bash
   terraform output user_app_role_arn
   ```
2. **BE 설정** — 업로드 대상 버킷(`USER_PROFILE_IMAGE_BUCKET`)이 출력 `asset_bucket_name`
   (`victoryfairy-asset`)과 같은 문자열이어야 한다.
3. **키 접두사 일치 확인** — `temp/`, `user-profile-img/` 는 BE 업로드 키·버킷 정책·
   CloudFront 경로 패턴·IRSA 정책이 **문자 그대로** 같아야 한다. 어긋나면 업로드는
   AccessDenied, 조회는 CloudFront 가 FE 버킷으로 보내 404 다.

## 7. 이 스택에 걸린 가드

`aws_s3_bucket.this`(asset 버킷)에 **`prevent_destroy`** 가 걸려 있다. 버저닝도 백업도 없는
사용자 데이터라, 버킷 이름을 건드리는 순간의 재생성(=업로드 전량 유실)을 **plan 단계에서
에러로 막기 위한 것**이다. 의도된 동작이며, 정말 지워야 할 때의 해제 절차는
`modules/asset/main.tf` §1 주석에 있다. ⚠ 이 가드 때문에 `environments/dev` 전체의
`terraform destroy` 도 실패한다(역시 의도된 것).

## 8. 이 apply 가 영구히 가져가는 URL 공간

behavior 두 개가 apex 도메인의 경로 두 갈래를 **asset 버킷 몫으로 확정한다.**

| 경로 | 가는 곳 |
|---|---|
| `/user-profile-img/*` | asset 버킷 |
| `/temp/*` | asset 버킷 |

⚠ 특히 **`/temp/*` 는 이름이 일반적이라 나중에 부딪히기 쉽다.** 앞으로 FE 가 `/temp` 로
시작하는 라우트(예: `/temp-preview`가 아니라 `/temp/...`)를 쓰면 그 요청은 FE 버킷이 아니라
asset 버킷으로 가고, 객체가 없으니 **SPA fallback 도 타지 않고 조용히 404** 가 된다
(이 behavior 에는 fallback 함수를 붙이지 않았다 — 없는 이미지에 index.html 을 돌려주지 않기
위해서다). FE 라우트를 정할 때 이 두 접두사를 피하고, 꼭 필요하면 접두사(`var.asset_temp_prefix`)
를 바꾸는 쪽으로 푼다 — BE 업로드 키·버킷 정책·IRSA 정책이 함께 움직여야 한다.
