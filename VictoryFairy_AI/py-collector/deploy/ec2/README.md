# EC2 크론 배포 (선수·라인업 DB 잡)

MySQL 에 쓰는 DB 잡(`registrations`, `records`)을 서비스가 떠 있는 EC2 에서
크론으로 돌린다. Lambda(deploy/lambda)는 S3 전용이라 DB 잡을 못 돌리고,
EC2 는 RDS 와 같은 VPC 라 터널 없이 접속된다.

| 파일 | 역할 |
|---|---|
| `run_collector.sh` | GHCR 이미지(pull→run) 래퍼. 잡당 `--memory 300m`, 로그 `logs/` |
| `victoryfairy-collector.cron` | `/etc/cron.d/` 스케줄 (records 03:30 KST · registrations 11:00 KST) |
| `collector.env.example` | `collector.env` 템플릿 (RDS 접속 정보, 커밋 금지) |

이미지는 `Dockerfile.run` 으로 빌드되는 `ghcr.io/<owner>/victoryfairy-collector`
이며, `.github/workflows/deploy.yml` 이 `py-collector/**` 변경 시 자동 빌드·푸시하고
이 디렉터리 파일들을 `~/app/collector/` 로 복사 + 크론을 설치한다.

## 최초 1회 수동 세팅 (배포 전)

1. **DB 마이그레이션** — 운영 RDS 에 순서대로 실행 (SSH 터널로 로컬에서):
   ```bash
   # 구 수집기 스키마가 있는지 먼저 확인: 아래가 team_code PK 면 구 스키마
   mysql -h 127.0.0.1 -u vf -p victoryfairy -e 'SHOW CREATE TABLE teams\G'
   mysql -h 127.0.0.1 -u vf -p victoryfairy < ../sql/migrate-legacy-collector.sql
   mysql -h 127.0.0.1 -u vf -p victoryfairy < ../sql/schema.sql
   ```
2. **collector.env 생성** — EC2 에서:
   ```bash
   mkdir -p ~/app/collector
   cp collector.env.example ~/app/collector/collector.env   # 값 채우기
   chmod 600 ~/app/collector/collector.env
   ```
3. **KBO 공식 사이트 접근 확인** — registrations 는 KBO 사이트를 긁는다.
   EC2(AWS IP)에서 차단이면 registrations 크론만 로컬로 되돌려야 한다:
   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' https://www.koreabaseball.com/Player/Register.aspx
   ```

## 배포 후 확인 / 수동 실행

```bash
# 크론 설치 확인
cat /etc/cron.d/victoryfairy-collector
# 수동 1회 실행 (오늘 로스터 / 어제 경기)
~/app/collector/run_collector.sh registrations
~/app/collector/run_collector.sh records
# 시즌 백필 (예: 개막일부터)
~/app/collector/run_collector.sh records --from 2026-03-28 --to 2026-07-22
# 로그
tail -f ~/app/collector/logs/$(date -u +%Y%m%d)-records.log
# 적재 확인
mysql ... -e 'SELECT COUNT(*) FROM games; SELECT COUNT(*) FROM game_lineups;'
```

## 메모리

t3.small(2GB)에서 앱 3개(500m×3) + nginx(128m) 예산에 크론 잡 300m 이 겹친다.
크론 시각(03:30/11:00 KST)은 트래픽 저점이라 겹침 부담이 작고, 초과 시 해당
컨테이너만 OOM-kill 된다(호스트 생존 전략은 docker-compose.prod.yml 주석 참고).
