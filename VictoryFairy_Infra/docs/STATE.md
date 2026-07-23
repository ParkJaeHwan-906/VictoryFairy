# Terraform State 관리 (S3 원격 백엔드)

> 이 문서는 **tfstate를 어디에 두고, 여러 기기에서 어떻게 안전하게 공유하는지**를 기록한다.
> - 백엔드 설정 코드: `environments/dev/terraform.tf`
> - 규약: `.claude/skills/terraform-infra/SKILL.md` §2
> - 배경 결정 기록: 아래 §1

## 1. 왜 S3 원격 백엔드인가 (결정 기록)

초기 구축은 특정 기기의 **로컬 state**(`terraform.tfstate`)로 진행됐다. 그 결과
다른 기기에서 같은 레포를 열면 state가 없어 **plan이 "전체 신규 생성(54개 리소스)"으로
나오는** 문제가 발생했다(2026-07-23 발견). 이 상태에서 apply하면 기존 인프라를
인식하지 못하고 중복 생성을 시도한다.

해법으로 규약(§2)대로 **S3 원격 백엔드 + DynamoDB 락**을 활성화했다:

| 리소스 | 이름 | 비고 |
|--------|------|------|
| S3 버킷 | `victoryfairy-tfstate` | 버저닝 ON(복구용), SSE-S3 암호화, 퍼블릭 전면 차단 |
| DynamoDB 테이블 | `victoryfairy-tflock` | 파티션 키 `LockID`, on-demand — 동시 apply 방지 락 |
| state key | `dev/terraform.tfstate` | 환경별 분리(prod 추가 시 `prod/…`) |

두 리소스는 **Terraform 밖에서 수동 생성**했다(2026-07-23). 백엔드 저장소 자체를
Terraform으로 만들면 닭-달걀 문제가 생기므로 의도된 예외다(태그 `ManagedBy=Manual-Backend`).

## 2. 최초 1회: 기존 로컬 state 이관

**로컬 state를 가진 기기에서** 실행한다 (다른 기기에서 하면 안 됨 — 이관할 원본이 없다):

```bash
git pull                                  # backend 활성화된 terraform.tf 수신
cd VictoryFairy_Infra/environments/dev
terraform init -migrate-state             # 물으면 "yes" → 로컬 state가 S3로 업로드
terraform state list                      # 기존 리소스 목록이 그대로 보이면 성공
```

- 이관 후에도 로컬 `terraform.tfstate`·`.backup` 파일은 **바로 지우지 말고** 당분간
  백업으로 보관한다(어차피 gitignore 대상).
- 이관은 최초 1회만. 이후 모든 기기는 §3 절차만 따르면 된다.

## 3. 새 기기 온보딩 (이관 완료 후)

```bash
cd VictoryFairy_Infra/environments/dev
cp terraform.tfvars.example terraform.tfvars   # 값 확인(backup_s3_bucket 등)
terraform init                                  # S3 백엔드 연결
terraform plan                                  # 변경 없으면 "no changes"가 정상
```

## 4. 안전 수칙

- **plan이 "전체 신규 생성"으로 나오면 즉시 중단.** state에 연결되지 않았다는 신호다
  (이관 미완료·자격증명 문제·잘못된 디렉터리). 그 상태의 apply는 인프라 중복 생성 사고다.
- state 존재 확인: `aws s3 ls s3://victoryfairy-tfstate/dev/`
- 동시 apply는 DynamoDB 락이 차단한다. `Error acquiring the state lock`이 뜨면 다른
  기기의 작업이 끝나길 기다린다. (진짜 고아 락일 때만 `terraform force-unlock <ID>`)
- state 복구: 버킷 버저닝이 켜져 있으므로 S3 콘솔에서 이전 버전을 복원할 수 있다.
- `*.tfstate*`는 **절대 Git에 커밋하지 않는다**(gitignore 처리됨). 커밋 대상은
  `.terraform.lock.hcl`(프로바이더 버전 고정)뿐이다.
