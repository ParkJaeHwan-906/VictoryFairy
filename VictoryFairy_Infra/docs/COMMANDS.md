# 자주 사용하는 명령어 모음

> 배포 절차의 배경 설명은 → [DEPLOYMENT.md](DEPLOYMENT.md)
> 실행 위치는 특별한 언급이 없으면 `VictoryFairy_Infra/` 기준.

## 접속 (터널)

```bash
./scripts/db-tunnel.sh start     # MySQL(3306)·Redis(6379)·SSH(2222) 터널 열기
./scripts/db-tunnel.sh status    # 터널 상태 확인
./scripts/db-tunnel.sh stop      # 터널 종료
```

| 도구 | 접속 정보 |
|---|---|
| HeidiSQL / DBeaver | `127.0.0.1:3306`, 사용자 `hwannee` |
| RedisInsight / redis-cli | `127.0.0.1:6379` (비밀번호 없음) |
| Termius / MobaXterm (SSH) | `127.0.0.1:2222`, 사용자 `ec2-user`, 키 `VictoryFairy.pem`, 비밀번호 비움 |

```bash
# 서버 셸 (터널 없이 바로 — SSM 세션)
aws ssm start-session --region ap-northeast-2 --target $(terraform -chdir=environments/dev output -raw mysql_instance_id)
```

## 배포

```bash
./scripts/deploy-app.sh                    # user·quiz 빌드→ECR→배포 (태그=커밋SHA)
MODULES="quiz" ./scripts/deploy-app.sh     # 특정 모듈만
./scripts/deploy-app.sh <태그>             # 특정 버전 재배포/롤백 (빌드 생략)
```

```bash
# 롤백
kubectl -n victoryfairy rollout undo deploy/user-app                  # 직전 버전
kubectl -n victoryfairy rollout undo deploy/user-app --to-revision=2  # 특정 리비전
kubectl -n victoryfairy rollout history deploy/user-app               # 배포 이력
```

## 쿠버네티스 상태 확인

```bash
aws eks update-kubeconfig --region ap-northeast-2 --name victoryfairy-dev  # 최초 1회

kubectl get nodes -L workload                    # 노드 목록 (+워크로드 라벨)
kubectl -n victoryfairy get pods -o wide         # 앱 파드 상태
kubectl -n victoryfairy get hpa                  # 오토스케일 상태 (파드)
kubectl top nodes                                # 노드 CPU/메모리 사용량
kubectl top pods -n victoryfairy                 # 파드 CPU/메모리 사용량
```

## 앱 디버깅

```bash
kubectl -n victoryfairy logs -f deploy/user-app          # 로그 실시간
kubectl -n victoryfairy logs deploy/quiz-app --tail=100  # 최근 100줄
kubectl -n victoryfairy exec -it deploy/user-app -- sh   # 컨테이너 셸
kubectl -n victoryfairy describe pod <파드명>            # 파드 상세(이벤트 포함)
kubectl -n victoryfairy get events --sort-by=.lastTimestamp | tail -20  # 최근 이벤트
```

## 앱 설정 (환경변수)

```bash
kubectl -n victoryfairy get configmap app-config -o yaml   # 설정 확인
kubectl -n victoryfairy edit configmap app-config          # 설정 수정
# Secret 수정 후에는 파드 재시작 필요:
kubectl -n victoryfairy rollout restart deploy/user-app deploy/quiz-app
```

## ECR (이미지)

```bash
# 보관 중인 이미지 태그 목록
aws ecr describe-images --repository-name victoryfairy-user \
  --query "sort_by(imageDetails,&imagePushedAt)[].imageTags[0]" --output table
```

## DB 서버 점검 (SSM 원격 명령)

```bash
ID=$(terraform -chdir=environments/dev output -raw mysql_instance_id)

# 컨테이너 상태
aws ssm send-command --instance-ids $ID --document-name AWS-RunShellScript \
  --parameters 'commands=["docker ps"]' --query Command.CommandId --output text
# → 결과 조회: aws ssm get-command-invocation --command-id <위 출력> --instance-id $ID \
#              --query StandardOutputContent --output text
```

## Terraform (인프라 변경)

```bash
cd environments/dev
terraform fmt -recursive ../..   # 포맷
terraform validate               # 문법 검증
terraform plan                   # 변경 미리보기 (⚠ destroy/replace 표시 반드시 확인)
terraform apply                  # 적용
terraform output                 # 출력값 확인 (인스턴스 ID, 역할 ARN 등)
```

## 오토스케일 관찰

```bash
kubectl -n kube-system logs deploy/cluster-autoscaler --tail=30   # CA 판단 로그
kubectl -n victoryfairy get hpa -w                                # HPA 실시간 감시
kubectl get nodes -w                                              # 노드 증감 실시간 감시
```

## 부하 테스트 (오토스케일 동작 확인용)

```bash
kubectl -n victoryfairy run load --rm -it --image=busybox --restart=Never -- \
  /bin/sh -c "while true; do wget -qO- http://quiz-app/api/quiz >/dev/null 2>&1; done"
# 별도 터미널에서: kubectl -n victoryfairy get hpa -w   (CPU% 상승 → REPLICAS 증가 관찰)
```
