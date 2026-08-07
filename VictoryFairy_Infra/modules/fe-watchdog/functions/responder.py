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
import re
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

# {"user": "/api/actuator/health/readiness", ...} — healthcheck 와 같은 값을 받는다.
API_TARGETS = json.loads(os.environ.get("API_TARGETS", "{}"))

ASSET_RE = re.compile(r"assets/index-[A-Za-z0-9]+\.js")

# ⚠ 표시 이름(@박재환)은 알림을 울리지 않는다 — 일반 텍스트로만 보인다.
#   반드시 사용자 ID 로 <@U...> 형태여야 멘션이 된다.
MENTION_USER_IDS = [u for u in os.environ.get("MENTION_USER_IDS", "").split(",") if u]

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


def _status(path):
    """(상태코드, 본문). 도달 실패는 0."""
    req = urllib.request.Request(f"{SITE}{path}", headers={"User-Agent": "vf-watchdog/1"})
    try:
        with urllib.request.urlopen(req, timeout=8) as res:
            return res.status, res.read(200_000).decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, ""
    except Exception:  # noqa: BLE001
        return 0, ""


def probe():
    """알람 시점의 실제 증상을 모은다.

    CloudWatch 가 주는 NewStateReason 은 "Threshold Crossed: 2 datapoints..." 같은 기계적 문구라
    '무엇이 왜 깨졌는지' 를 알려주지 않는다. 그래서 여기서 직접 때려 본다 — 알림에 담을 값은
    지표가 아니라 사람이 바로 판단할 수 있는 응답 코드다.

    반환: [(라벨, 경로, 상태코드)], 진단문
    """
    rows = []

    code, body = _status("/")
    rows.append(("진입점", "/", code))

    # 번들 참조는 index.html 에서 뽑는다 — 버전이 바뀌면 파일명도 바뀌므로 고정할 수 없다.
    asset = None
    if code == 200:
        m = ASSET_RE.search(body)
        asset = m.group(0) if m else None
    if asset:
        acode, _ = _status(f"/{asset}")
        rows.append(("번들", f"/{asset}", acode))
    else:
        rows.append(("번들", "index.html 에서 참조를 찾지 못함", 0))

    rows.append(("딥링크", "/login", _status("/login")[0]))

    for name, path in API_TARGETS.items():
        rows.append((f"API({name})", path, _status(path)[0]))

    return rows, diagnose(rows)


def diagnose(rows):
    """응답 코드 조합에서 한 줄 판단을 뽑는다. 사람이 로그를 뒤지기 전에 방향을 잡게 하는 것이 목적."""
    by_label = {label: code for label, _, code in rows}
    entry = by_label.get("진입점", 0)
    bundle = by_label.get("번들", 0)
    deep = by_label.get("딥링크", 0)
    apis = {k: v for k, v in by_label.items() if k.startswith("API(")}
    api_bad = [k for k, v in apis.items() if v != 200]

    if entry == 0:
        return "도메인에 아예 닿지 못합니다. DNS·인증서·CloudFront 배포 상태를 먼저 보십시오."
    if entry != 200:
        return f"진입점이 {entry} 입니다. S3 의 index.html 이 없거나 CloudFront 오리진 접근이 막힌 상태로 보입니다."
    if bundle != 200:
        return (
            f"index.html 은 살아 있는데 그것이 참조하는 번들이 {bundle} 입니다. "
            "배포가 자산을 올리지 못했거나, index.html 과 자산 버전이 어긋난 상태입니다."
        )
    if deep != 200:
        return f"딥링크가 {deep} 입니다. CloudFront SPA fallback 함수를 확인하십시오."
    if api_bad:
        return (
            f"정적 자산은 정상인데 {', '.join(api_bad)} 가 실패합니다. "
            "FE 문제가 아니라 ALB 타깃·파드·앱 쪽입니다(kubectl get pods / describe ingress)."
        )
    return "지금 다시 때려보니 전부 정상입니다 — 이미 회복됐거나 간헐적 증상입니다(플래핑 가능)."


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
    """(티켓 URL, 실패 사유). 둘 중 하나만 값이 있다.

    ⚠ 실패 사유를 반환하는 이유: 토큰 만료 같은 이유로 티켓이 안 열렸을 때 조용히 넘어가면
      아무도 눈치채지 못한다. 알림에 그 사실을 실어 보내야 한다(감시의 실패가 침묵이 되면 안 된다).
      미설정은 의도된 상태이므로 사유를 반환하지 않는다.
    """
    token = _param(GITHUB_TOKEN_PARAM)
    if not token or not GITHUB_REPO:
        print("GitHub 토큰/레포 미설정 — 티켓 생략(의도된 상태)")
        return None, None
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
        if status < 300:
            return json.loads(raw).get("html_url"), None
        # 401·403 은 대개 토큰 만료·권한 부족이다. 사람이 바로 알아야 한다.
        return None, f"GitHub 응답 {status} (토큰 만료·권한 확인 필요)"
    except Exception as e:  # noqa: BLE001
        print(f"GitHub 티켓 발행 실패: {e}")
        return None, f"{type(e).__name__}: {e}"


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


def _table(rows):
    """응답 코드 표. 코드블록으로 감싸 Slack 에서 열이 맞게 보이도록 한다."""
    body = "\n".join(f"{label:<11}{path:<44}{code if code else '도달실패'}" for label, path, code in rows)
    return f"```\n{body}\n```"


def _mentions():
    return " ".join(f"<@{uid}>" for uid in MENTION_USER_IDS)


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

        # 복구는 멘션하지 않는다 — 좋은 소식으로 사람을 부르면 다음 진짜 호출이 무시된다.
        if state != "ALARM":
            notify_slack(f":white_check_mark: 복구됨 — `{name}`\n{reason}")
            continue

        rows, judgement = probe()

        head = ":rotating_light: *FE 장애*" if is_fe else f":rotating_light: *BE 장애* — `{name}`"
        lines = [_mentions(), head, f"알람: `{name}`", "", "*지금 상태*", _table(rows), f"*판단* {judgement}"]

        if not is_fe:
            lines += [
                "*조치* FE 롤백은 하지 않았습니다 — 원인이 BE 라 FE 를 되돌리면 정상 FE 만 잃습니다.",
                "*확인* `kubectl -n victoryfairy get pods` · `kubectl -n victoryfairy describe ingress`",
            ]
            notify_slack("\n".join(lines))
            continue

        ok, why, target = rollback()
        if ok:
            lines.append(f"*조치* `{target}` 로 자동 롤백했습니다. {SITE} 확인 필요.")
            issue = open_issue(
                f"[FE 자동 롤백] {target} 로 되돌림 — {judgement[:60]}",
                f"CloudWatch 알람 `{name}` 이 ALARM 으로 전이해 상시 감시가 자동 롤백했습니다.\n\n"
                f"| | |\n|---|---|\n"
                f"| 되돌린 버전 | `{target}` |\n"
                f"| 판단 | {judgement} |\n"
                f"| 알람 사유 | {reason} |\n\n"
                "## 알람 시점의 실제 응답\n\n"
                "```\n"
                + "\n".join(f"{l:<11}{p:<44}{c if c else '도달실패'}" for l, p, c in rows)
                + "\n```\n\n"
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
            issue_url, issue_err = issue
            if issue_url:
                lines.append(f"*티켓* {issue_url}")
            elif issue_err:
                # 티켓이 안 열린 것을 알림에 드러낸다 — 조용히 넘어가면 추적이 끊긴 줄도 모른다.
                lines.append(f":warning: *티켓 발행 실패* — {issue_err}\n손으로 이슈를 남겨주십시오.")
        else:
            lines.append(f":x: *자동 롤백 못 함* — {why}\n수동 대응이 필요합니다.")

        notify_slack("\n".join(lines))

    return {"ok": True}
