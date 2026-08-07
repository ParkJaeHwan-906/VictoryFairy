# FE 릴리스 롤백

FE 롤백은 **재빌드가 아니라 파일 하나를 되돌려 놓는 것**이다. 수 초 안에 끝나고 CI·빌드·git 어느
것도 필요하지 않다.

## 급할 때 — 이것만 보면 된다

```bash
B=victoryfairy-dev-fe

# 1) 배포 이력. 이름이 UTC 타임스탬프로 시작하므로 정렬 = 시간순.
#    끝에서 두 번째가 '이전 버전' 이다.
aws s3 ls s3://$B/releases/

# 2) 되돌리기 (이게 전부다)
aws s3 cp s3://$B/releases/20260807T120000Z-aecc7b1/index.html s3://$B/index.html

# 3) 확인 — 번들 파일명이 바뀌었는지 본다
curl -s https://victoryfairy.com/ | grep -o 'assets/index-[A-Za-z0-9]*\.js'
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

## 주의할 것

**롤백은 코드가 아니라 배포본만 되돌린다.** git 은 그대로다. 원인을 고쳐 다시 배포하기 전까지는
`main` 과 서비스 중인 버전이 어긋난 상태이므로, 롤백했다는 사실을 팀에 알려야 한다. 다음 배포가
돌면 그 버전으로 덮인다 — **고치지 않은 채 다른 변경을 배포하면 롤백이 조용히 취소된다.**

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
