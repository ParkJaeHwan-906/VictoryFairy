"""알람 대응 — SNS 로 들어온 CloudWatch 알람을 받아 롤백하고 알린다.

한 함수가 세 가지를 한다:
  1) Slack 알림 (모든 알람)
  2) FE 알람이면 이전 릴리스로 롤백  (API 알람은 하지 않는다 — 아래 참고)
  3) 롤백했으면 GitHub Issue 발행

⚠ FE 알람만 롤백한다. 깨진 원인이 API·BE 면 FE 를 되돌려도 낫지 않고 정상 FE 만 잃는다.
  healthcheck 가 FeHealthy / ApiHealthy 를 나눠 발행하고, 알람도 둘로 갈라 둔 이유가 이것이다.

⚠ 롤백 반복(계단식 되감기) 방지: CloudWatch 알람은 '상태 전이' 에만 통보하므로 ALARM 이 유지되는
  동안은 다시 호출되지 않는다. 이것이 1차 방어다. 그 위에 아래 두 가지를 둔다.
    - 되돌릴 이전 릴리스가 없으면 알림만 보낸다.
    - CURRENT 마커가 최근 ROLLBACK_COOLDOWN_SECONDS 안에 바뀌었으면 롤백을 건너뛴다
      (알람이 OK↔ALARM 로 요동칠 때 연속 되감기를 막는다).
"""

import datetime as dt
import json
import os
import urllib.error
import urllib.request

import boto3

BUCKET = os.environ["FE_BUCKET"]
RELEASES_PREFIX = os.environ.get("RELEASES_PREFIX", "releases/")
CURRENT_KEY = f"{RELEASES_PREFIX}CURRENT"
FE_ALARM_NAME = os.environ["FE_ALARM_NAME"]
SITE = os.environ["SITE_URL"].rstrip("/")
COOLDOWN = int(os.environ.get("ROLLBACK_COOLDOWN_SECONDS", "600"))

SLACK_WEBHOOK_PARAM = os.environ.get("SLACK_WEBHOOK_PARAM", "")
GITHUB_TOKEN_PARAM = os.environ.get("GITHUB_TOKEN_PARAM", "")
GITHUB_REPO = os.environ.get("GITHUB_REPO", "")

s3 = boto3.client("s3")
ssm = boto3.client("ssm")

_param_cache = {}


def _param(name):
    """SSM SecureString 조회. 없으면 None — 그 기능만 조용히 건너뛴다."""
    if not name:
        return None
    if name in _param_cache:
        return _param_cache[name]
    try:
        v = ssm.get_parameter(Name=name, WithDecryption=True)["Parameter"]["Value"]
    except Exception as e:  # noqa: BLE001
        print(f"SSM {name} 조회 실패 — 해당 기능 생략: {e}")
        v = None
    _param_cache[name] = v
    return v


def _post_json(url, payload, headers=None):
    data = json.dumps(payload).encode()
    req = urllib.request.Request(
        url, data=data, headers={"Content-Type": "application/json", **(headers or {})}
    )
    with urllib.request.urlopen(req, timeout=10) as res:
        return res.status, res.read().decode("utf-8", "replace")


def notify_slack(text, blocks=None):
    url = _param(SLACK_WEBHOOK_PARAM)
    if not url:
        print("Slack 웹훅 미설정 — 알림 생략")
        return
    try:
        payload = {"text": text}
        if blocks:
            payload["blocks"] = blocks
        _post_json(url, payload)
    except Exception as e:  # noqa: BLE001 - 알림 실패가 롤백을 막아선 안 된다
        print(f"Slack 알림 실패: {e}")


def open_issue(title, body):
    token = _param(GITHUB_TOKEN_PARAM)
    if not token or not GITHUB_REPO:
        print("GitHub 토큰/레포 미설정 — 티켓 생략")
        return None
    try:
        status, raw = _post_json(
            f"https://api.github.com/repos/{GITHUB_REPO}/issues",
            {"title": title, "body": body, "labels": ["fe-rollback"]},
            {
                "Authorization": f"Bearer {token}",
                "Accept": "application/vnd.github+json",
                "User-Agent": "vf-fe-watchdog/1",
            },
        )
        return json.loads(raw).get("html_url") if status < 300 else None
    except Exception as e:  # noqa: BLE001
        print(f"GitHub 티켓 발행 실패: {e}")
        return None


def list_releases():
    """releases/ 하위 버전 목록(정렬). 이름이 UTC 고정폭 타임스탬프라 정렬 = 시간순."""
    versions = []
    paginator = s3.get_paginator("list_objects_v2")
    for page in paginator.paginate(Bucket=BUCKET, Prefix=RELEASES_PREFIX, Delimiter="/"):
        for cp in page.get("CommonPrefixes", []):
            v = cp["Prefix"][len(RELEASES_PREFIX):].rstrip("/")
            if v:
                versions.append(v)
    return sorted(versions)


def read_current():
    """(현재 버전, 마커 최종수정시각). 마커가 없으면 (None, None)."""
    try:
        obj = s3.get_object(Bucket=BUCKET, Key=CURRENT_KEY)
        return obj["Body"].read().decode().strip(), obj["LastModified"]
    except s3.exceptions.NoSuchKey:
        return None, None
    except Exception as e:  # noqa: BLE001
        print(f"CURRENT 마커 조회 실패: {e}")
        return None, None


def rollback():
    """이전 릴리스로 되돌린다. (성공여부, 사유, 되돌린버전) 반환."""
    versions = list_releases()
    if len(versions) < 2:
        return False, f"릴리스가 {len(versions)}개뿐이라 되돌릴 대상이 없다", None

    current, modified = read_current()
    if current is None:
        # 마커가 없으면 최신을 현재로 가정한다(배포 워크플로가 마커를 쓰기 전 상태).
        current = versions[-1]
        print(f"CURRENT 마커 없음 — 최신({current})을 현재로 가정")
    elif modified is not None:
        age = (dt.datetime.now(dt.timezone.utc) - modified).total_seconds()
        if age < COOLDOWN:
            return False, f"쿨다운 — {int(age)}초 전에 이미 전환됐다(연속 되감기 방지)", None

    if current not in versions:
        return False, f"현재 버전 {current} 을 아카이브에서 찾을 수 없다", None

    idx = versions.index(current)
    if idx == 0:
        return False, f"{current} 이 가장 오래된 릴리스라 더 되돌릴 수 없다", None

    target = versions[idx - 1]
    s3.copy_object(
        Bucket=BUCKET,
        Key="index.html",
        CopySource={"Bucket": BUCKET, "Key": f"{RELEASES_PREFIX}{target}/index.html"},
    )
    s3.put_object(Bucket=BUCKET, Key=CURRENT_KEY, Body=target.encode(), ContentType="text/plain")
    return True, "ok", target


def handler(event, context):  # noqa: ARG001
    for record in event.get("Records", []):
        try:
            msg = json.loads(record["Sns"]["Message"])
        except Exception:  # noqa: BLE001
            notify_slack(f":warning: 파싱할 수 없는 알람 메시지: {record['Sns'].get('Message')}")
            continue

        name = msg.get("AlarmName", "?")
        state = msg.get("NewStateValue", "?")
        reason = msg.get("NewStateReason", "")
        is_fe = name == FE_ALARM_NAME

        # 복구(OK) 통보는 알리기만 한다.
        if state != "ALARM":
            notify_slack(f":white_check_mark: *{name}* 복구됨 ({state})\n{reason}")
            continue

        lines = [f":rotating_light: *{name}* ALARM", reason]

        if not is_fe:
            lines.append(
                "\nFE 롤백은 하지 않습니다 — 이 알람은 API/오리진 범주이고 FE 를 되돌려도 낫지 않습니다."
            )
            notify_slack("\n".join(lines))
            continue

        ok, why, target = rollback()
        if ok:
            lines.append(f"\n:leftwards_arrow_with_hook: *{target}* 로 자동 롤백했습니다. {SITE} 확인 필요.")
            issue = open_issue(
                f"[FE 자동 롤백] {target} 로 되돌림 — {name}",
                f"CloudWatch 알람 `{name}` 이 ALARM 으로 전이해 상시 감시가 자동 롤백했습니다.\n\n"
                f"| | |\n|---|---|\n"
                f"| 되돌린 버전 | `{target}` |\n"
                f"| 알람 사유 | {reason} |\n\n"
                "## ⚠ 지금 상태\n\n"
                "롤백은 **배포본만** 되돌립니다. `main` 은 그대로이므로 코드와 서비스 중인 버전이 "
                "어긋나 있습니다. **원인을 고치지 않은 채 다른 변경을 배포하면 롤백이 조용히 "
                "취소됩니다.**\n\n"
                "## 할 일\n\n"
                "- [ ] 원인 파악\n"
                "- [ ] 수정 후 배포 (성공하면 이 티켓을 닫는다)\n"
                "- [ ] 배포 스모크가 이 유형을 잡았는지 확인 — 못 잡았다면 검사 항목 추가\n\n"
                "절차: `VictoryFairy_Infra/docs/fe-release-rollback.md`",
            )
            if issue:
                lines.append(f"티켓: {issue}")
        else:
            lines.append(f"\n:x: *자동 롤백 못 함* — {why}\n수동 대응이 필요합니다.")

        notify_slack("\n".join(lines))

    return {"ok": True}
