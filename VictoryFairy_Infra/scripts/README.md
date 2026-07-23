# scripts/ — 운영 보조 스크립트

> 자주 쓰는 명령어 전체 모음은 → [../docs/COMMANDS.md](../docs/COMMANDS.md)
> 배포 스크립트(deploy-app.sh) 사용법은 → [../docs/DEPLOYMENT.md](../docs/DEPLOYMENT.md)

## db-tunnel.sh — DB·SSH 접속 터널

데이터 EC2는 프라이빗 서브넷에 있고 22/3306/6379 인바운드를 일절 열지 않습니다
(보안 설계 — [ARCHITECTURE.md](../docs/ARCHITECTURE.md) 참고). EKS 워커 노드도
동일하게 22 인바운드를 인터넷에 열지 않습니다(소스 = 클러스터 SG 한정).
로컬에서 DB 클라이언트(HeidiSQL 등)나 SSH 클라이언트(Termius 등)로 접속하려면
**AWS SSM Session Manager 포트포워딩 터널**을 사용합니다. 이 스크립트가 그 터널을
백그라운드로 관리해줍니다.

```bash
./scripts/db-tunnel.sh start    # 작업 시작할 때 한 번 (MySQL·Redis·SSH·노드SSH 터널 동시)
./scripts/db-tunnel.sh status   # 터널 상태 확인
./scripts/db-tunnel.sh stop     # 끝나면 정리 (안 해도 무방)
```

`start` 후에는 터미널을 닫아도 터널이 유지됩니다.

### 접속 정보 (터널 열린 상태 기준)

| 도구 | 호스트:포트 | 계정 | 인증 |
|---|---|---|---|
| HeidiSQL / DBeaver (MySQL) | `127.0.0.1:3306` | `hwannee` (어드민에게 발급 문의) | 비밀번호 |
| RedisInsight / redis-cli | `127.0.0.1:6379` | — | 없음 (클러스터 내부 전용 브로커) |
| Termius / MobaXterm (SSH, 데이터 EC2) | `127.0.0.1:2222` | `ec2-user` | **pem 키** (비밀번호 비움) |
| Termius / MobaXterm (SSH, EKS app 노드) | `127.0.0.1:2223` | `ec2-user` | **pem 키** `VictoryFairy.pem` (비밀번호 비움) |

- HeidiSQL: 네트워크 유형 **MariaDB or MySQL (TCP/IP)**, "SSH 터널" 탭은 사용하지
  않음(터널은 스크립트가 이미 열어줌).
- Termius: Keys에 `VictoryFairy.pem` 등록(Passphrase 비움) 후 위 정보로 접속.
  ⚠ 데이터 EC2(2222)는 사전에 본인 공개키가 EC2 `ec2-user`의 authorized_keys 에
  등록되어 있어야 합니다(어드민에게 요청 — SSM 원격 명령으로 등록). EKS 노드(2223)는
  키페어 `VictoryFairy`(pem) 그대로 사용하면 됩니다(온보딩 시 어드민에게 pem 요청).
- 서버 셸만 필요하면 SSH 대신 SSM 세션이 더 간단합니다:
  `aws ssm start-session --region ap-northeast-2 --target <인스턴스ID>`
- EKS 노드 SSH(2223)는 오토스케일로 뜨는 여러 노드 중 하나(태그로 자동 탐색)에
  접속됩니다. **파드/컨테이너 디버깅만 필요하면 SSH 없이도** 아래로 충분합니다:
  ```bash
  kubectl exec -it <파드> -n victoryfairy -- sh          # 파드 안으로
  kubectl debug node/<노드이름> -it --image=busybox      # 노드 자체 디버깅
  ```

### 최초 1회 설정 (팀원 온보딩)

1. **AWS CLI 설치** — https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html
2. **Session Manager 플러그인 설치**
   ```bash
   # macOS (Homebrew)
   brew install --cask session-manager-plugin

   # macOS (Homebrew 없이): AWS 공식 pkg 설치
   # https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-install-plugin.html
   ```
   Windows는 위 공식 문서의 Windows 설치 프로그램 사용.
3. **AWS 자격 증명 설정** — 어드민에게 IAM 액세스 키를 발급받아:
   ```bash
   aws configure   # 리전: ap-northeast-2
   ```
   최소 필요 권한: `ssm:StartSession`(대상 인스턴스), `ec2:DescribeInstances`.
4. 동작 확인:
   ```bash
   ./scripts/db-tunnel.sh start
   ./scripts/db-tunnel.sh status   # [mysql] ✅ / [redis] ✅ 가 나오면 성공
   ```

### 인스턴스 ID 조회 방식

스크립트는 대상 EC2를 다음 순서로 자동 탐색하므로 보통 신경 쓸 필요가 없습니다.

**데이터 EC2** (mysql/redis/ssh 터널)
1. 환경변수 `VF_DB_INSTANCE_ID` (수동 지정 시)
2. `terraform output` (apply를 실행한 머신 — 로컬 state 필요)
3. EC2 `Name=victoryfairy-mysql-dev` 태그 조회 (팀원 — AWS API)

**EKS app 노드** (node-ssh 터널) — 노드는 오토스케일로 계속 교체되므로 항상 태그로만 찾습니다.
1. 환경변수 `VF_NODE_INSTANCE_ID` (특정 노드를 골라 접속하고 싶을 때 수동 지정)
2. EKS 노드그룹 태그(`eks:cluster-name=victoryfairy-dev`, `eks:nodegroup-name=victoryfairy-dev-app`) 조회 → running 중 첫 번째 노드

### 자주 겪는 문제

| 증상 | 해결 |
|---|---|
| `❌ 닫힘` / 접속 안 됨 | `./scripts/db-tunnel.sh start` 재실행. 로그: `/tmp/vf-tunnel-*.log` |
| 20분쯤 지나 끊김 | SSM 세션 유휴 타임아웃(기본 20분). `start` 다시 실행 |
| `SessionManagerPlugin is not found` | 위 2번 플러그인 설치 |
| `TargetNotConnected` | 자격 증명 확인: `aws sts get-caller-identity` (555209622409 계정이어야 함) |
| 로컬 3306 충돌 (로컬 MySQL 사용 중) | `stop` 후 스크립트의 `TUNNELS` 매핑에서 로컬 포트를 13306 등으로 수정 |

### 이 방식을 쓰는 이유 (요약)

- DB에 퍼블릭 IP·개방 포트가 없어 인터넷에서 공격 표면이 0
- SSH 키(pem) 관리 불필요 — 팀원 온·오프보딩은 IAM 권한 부여/회수로 끝
- 모든 접속이 CloudTrail에 감사 기록됨

## kubectl — EKS 클러스터 접속

DB·EKS 노드 SSH와 달리 **EKS API 서버는 로컬에서 직접** 붙습니다(SSM 터널
불필요, 퍼블릭 엔드포인트 + IAM 인증). ⚠ `kubectl`은 클러스터/노드 "안"이 아니라
**로컬 PC에서 실행**하는 명령입니다.

```bash
aws eks update-kubeconfig --region ap-northeast-2 --name victoryfairy-dev
kubectl get nodes
```

- `error: You must be logged in to the server (Unauthorized)` → 어드민에게 EKS
  Access Entry 등록을 요청하세요(`modules/security` — IAM 사용자/역할별 클러스터 접근 등록).
- 노드 SSH(위 db-tunnel.sh 절의 2223)는 예외적인 저수준 디버깅용이고, 평소
  파드/노드 확인은 `kubectl get/describe/logs/exec`로 충분합니다.

## Kubernetes Dashboard (학습용 웹 UI)

`k8s/90-kubernetes-dashboard.yaml` + `k8s/91-dashboard-admin-user.yaml` 로 배포되는
쿠버네티스 학습용 대시보드입니다. **외부 노출 없음**(ClusterIP만, 접근은 로컬
port-forward로 한정) — ALB/NodePort로 열지 마세요.

```bash
kubectl apply -f k8s/90-kubernetes-dashboard.yaml -f k8s/91-dashboard-admin-user.yaml

kubectl -n kubernetes-dashboard port-forward svc/kubernetes-dashboard 8443:443
# 브라우저: https://localhost:8443  (자체서명 인증서 경고는 무시)

kubectl -n kubernetes-dashboard get secret admin-user-token \
  -o jsonpath='{.data.token}' | base64 -d   # 로그인 토큰
```

⚠ `admin-user`는 **cluster-admin 전권**을 가진 학습용 계정입니다. `victoryfairy-dev`
클러스터 한정으로만 쓰고, 운영 전환 전 반드시 제거하세요.
