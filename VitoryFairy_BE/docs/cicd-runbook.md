# CI/CD 런북 — main merge → EC2 자동 배포

> GitHub Actions에서 이미지 빌드 → GHCR push → EC2가 pull & 재시작

## 전체 흐름

```
PR을 main에 merge (= push)
        │
        ▼
GitHub Actions (.github/workflows/deploy.yml)
  1) build-and-push : user/quiz/create 3개 모듈 이미지 빌드
                      → ghcr.io/parkjaehwan-906/victoryfairy-<module>:<sha>, :latest
  2) deploy         : EC2에 SSH 접속
                      → docker-compose.prod.yml 전송
                      → docker compose pull && up -d
        │
        ▼
EC2: 새 이미지로 컨테이너 재기동
```

빌드를 GitHub 러너에서 하는 이유: **t2.micro(1GB RAM)에서 Gradle로 3개 모듈을 빌드하면 메모리 부족(OOM)으로 거의 실패**하기 때문. EC2는 "받아서 실행"만 한다.

---

## 1회성 세팅

### A. GitHub Secrets 등록
Repo → Settings → Secrets and variables → Actions → New repository secret

| 이름 | 값 |
|------|-----|
| `EC2_HOST` | EC2 퍼블릭 IP 또는 도메인 |
| `EC2_USER` | `ec2-user` (Amazon Linux 기준) |
| `EC2_SSH_KEY` | EC2 접속용 **private key 전체 내용** (`.pem` 파일 내용 그대로) |

> `GITHUB_TOKEN`은 자동 제공되므로 등록 불필요 (GHCR push/pull 인증에 사용).

### B. EC2 준비 (서버에서 1회 실행)

```bash
# Docker / compose 플러그인 설치 (Amazon Linux 2023)
sudo dnf install -y docker
sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user        # 재로그인 후 sudo 없이 docker 사용
mkdir -p ~/docker-cli-plugins
curl -SL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 \
  -o ~/.docker/cli-plugins/docker-compose 2>/dev/null || \
  (mkdir -p ~/.docker/cli-plugins && curl -SL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 -o ~/.docker/cli-plugins/docker-compose)
chmod +x ~/.docker/cli-plugins/docker-compose
docker compose version    # 확인

# 배포 디렉터리 + 환경변수 파일 생성
mkdir -p ~/app
cat > ~/app/.env <<'EOF'
DB_HOST=mysql
DB_PORT=3306
DB_NAME=victoryfairy
DB_USERNAME=hwannee
DB_PASSWORD=변경하세요
SPRING_PROFILES_ACTIVE=prod
JWT_SECRET=변경하세요
EOF
chmod 600 ~/app/.env
```

> `.env`는 git에 올라가지 않으므로(=.gitignore) **EC2에 직접 만들어 둔다.** 운영용 비밀번호/시크릿은 로컬 `.env`와 다른 값으로 쓰는 걸 권장.

### C. 보안 그룹 인바운드
앱 포트를 외부에서 접근하려면 8080/8081/8082(TCP) 개방 (또는 앞단에 nginx/ALB).

---

## ⚠️ RAM 경고 (중요)

빌드뿐 아니라 **실행**도 t2.micro 1GB에서는 빠듯하다.
Spring Boot 앱 3개 + MySQL을 동시에 띄우면 1GB를 초과해 OOM/스왑이 발생할 수 있다.

대응 옵션:
1. **스왑 메모리 추가** (학습용 임시방편)
   ```bash
   sudo dd if=/dev/zero of=/swapfile bs=128M count=16   # 2GB
   sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
   echo '/swapfile swap swap defaults 0 0' | sudo tee -a /etc/fstab
   ```
2. **JVM 힙 제한** — 각 모듈 Dockerfile/실행에 `-XX:MaxRAMPercentage=25` 등
3. **인스턴스 업그레이드** — `t3.small`(2GB) 이상 권장
4. 모듈을 모두 띄우지 말고 필요한 것만 실행

---

## 동작 확인 / 트러블슈팅

```bash
# 배포 후 EC2에서
cd ~/app
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f user

# 수동 배포(테스트)
export IMAGE_PREFIX=ghcr.io/parkjaehwan-906
export IMAGE_TAG=latest
docker login ghcr.io -u <github-id>        # PAT(read:packages) 입력
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d
```

- **GHCR pull 403/denied**: 패키지가 private이고 로그인이 안 된 경우. 워크플로의 `GITHUB_TOKEN` 로그인이 동작하지만, 수동 테스트 시엔 `read:packages` 권한 PAT로 `docker login` 필요. 혹은 GHCR 패키지를 public으로 전환.
- **SSH 실패**: `EC2_SSH_KEY`에 private key 전체(`-----BEGIN ... END-----` 포함), `EC2_HOST`/`EC2_USER` 확인. 보안 그룹 22번 포트 개방.
- **롤백**: 특정 커밋으로 되돌리려면 EC2에서 `IMAGE_TAG=<원하는-sha>`로 `pull && up -d`.
```
