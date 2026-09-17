"""상시 점검 — EventBridge 가 주기적으로 호출하고 결과를 CloudWatch 커스텀 지표로 발행한다.

FE(정적 자산)와 BE 모듈(user·quiz)을 한 번에 본다. 사용자 대면 URL 로 요청하므로
DNS → CloudFront → ALB → 파드 → 앱 전 구간을 통과하는 점검이다.

왜 지표를 거쳐 알람을 쓰는가: 여기서 바로 롤백하지 않는 이유는 '연속 N회 실패' 판정이 필요하기
때문이다. 일시적 네트워크 흔들림 한 번에 정상 버전을 되돌리면 자동화가 장애 원인이 된다.
그 디바운스는 CloudWatch 알람의 datapoints_to_alarm 이 해준다.

⚠ FE 와 BE 를 반드시 나눠 발행한다. 깨진 원인이 BE 면 FE 를 되돌려도 낫지 않고 정상 FE 만 잃는다.
  그래서 롤백은 FeHealthy 알람에만 연결하고 BE 알람은 알림만 보낸다.

⚠ 에러율 지표로는 FE 실패를 못 잡는다 — 번들이 깨져도 S3·CloudFront 는 200 을 준다. 그래서
  '응답이 왔는지' 가 아니라 '올바른 것이 왔는지' 를 본다: index.html 이 가리키는 자산을 실제로
  받아보는 것이 이 점검의 핵심이다.
"""

import json
import os
import re
import time
import urllib.error
import urllib.request

import boto3

SITE = os.environ["SITE_URL"].rstrip("/")
NAMESPACE = os.environ["METRIC_NAMESPACE"]
TIMEOUT = int(os.environ.get("HTTP_TIMEOUT_SECONDS", "10"))

# {"user": "/api/actuator/health/readiness", "quiz": "/rt/actuator/health/readiness"}
API_TARGETS = json.loads(os.environ.get("API_TARGETS", "{}"))

# 진입 스크립트 추출 — 파일명 '형식' 을 가정하지 않는다.
#
# ⚠ 종전에는 `assets/index-<해시>.js` 를 직접 긁었다. Vite 의 해시가 base64url 알파벳이라
#   '-'·'_' 가 끼면 매칭이 실패했고, 그 배포 동안 멀쩡한 FE 가 계속 실패로 찍혀 알람이
#   정상 릴리스를 되돌렸다(2026-08-11 #317 → 워크플로만 고치고 여기를 빠뜨려 08-14 재발).
#   해시 알파벳을 넓히는 것만으로는 부족하다 — 해시 길이·자산 경로·진입 청크 이름은 Vite
#   설정과 버전에 따라 언제든 바뀌고, 그때 같은 자리에서 같은 방식으로 또 깨진다.
#   그래서 '이름이 무엇인가' 가 아니라 'HTML 이 무엇을 불러오는가' 를 본다.
#
# 이 덕분에 '찾지 못함' 이 비로소 진짜 장애 신호가 된다: type="module" 스크립트가 없다는 것은
# 형식을 못 읽었다는 뜻이 아니라 SPA 가 부팅할 수 없다는 뜻이다.
SCRIPT_RE = re.compile(r"<script\b([^>]*)>", re.IGNORECASE)
SRC_RE = re.compile(r"""\bsrc=["']([^"']+)["']""", re.IGNORECASE)
TYPE_MODULE_RE = re.compile(r"""\btype=["']module["']""", re.IGNORECASE)

cloudwatch = boto3.client("cloudwatch")


def _get(path):
    """(상태코드, 본문, 소요ms). 연결 실패는 상태코드 0 으로 본다."""
    url = f"{SITE}{path}"
    req = urllib.request.Request(url, headers={"User-Agent": "vf-watchdog/1"})
    started = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as res:
            body = res.read(200_000).decode("utf-8", "replace")
            return res.status, body, (time.monotonic() - started) * 1000
    except urllib.error.HTTPError as e:
        return e.code, "", (time.monotonic() - started) * 1000
    except Exception:  # noqa: BLE001 - DNS/TLS/타임아웃 전부 '도달 실패' 로 묶는다
        return 0, "", (time.monotonic() - started) * 1000


def entry_bundle(html):
    """index.html 이 부르는 진입 스크립트 경로. 없으면 None.

    속성 순서(type/src)·따옴표 종류·상대경로를 가리지 않는다 — 번들러가 어떻게 내보내든
    '모듈 스크립트의 src' 라는 사실만 붙잡는다.
    """
    for attrs in SCRIPT_RE.findall(html):
        if not TYPE_MODULE_RE.search(attrs):
            continue
        m = SRC_RE.search(attrs)
        if m:
            path = m.group(1)
            return path if path.startswith("/") else "/" + path
    return None


def check_fe():
    """FE 가 실제로 동작하는지. (정상여부, 사유, 소요ms)."""
    status, body, ms = _get("/")
    if status != 200:
        return False, f"/ 가 {status}", ms

    # 진입점이 가리키는 번들을 실제로 받아본다. 이 확인이 핵심이다 —
    # index.html 만 200 이면 자산이 사라져도 통과해버려 점검이 무의미해진다.
    asset = entry_bundle(body)
    if asset is None:
        return False, "index.html 에 모듈 스크립트가 없다 — SPA 가 부팅하지 못한다", ms

    status, _, _ = _get(asset)
    if status != 200:
        return False, f"진입 번들 {asset} 이 {status}", ms

    # 딥링크 — SPA fallback 함수가 살아 있는지.
    status, _, _ = _get("/login")
    if status != 200:
        return False, f"딥링크 /login 이 {status}", ms

    return True, "ok", ms


def check_api(path):
    status, _, ms = _get(path)
    return (status == 200), (f"{path} 가 {status}" if status != 200 else "ok"), ms


def handler(event, context):  # noqa: ARG001
    metrics = []
    result = {}

    fe_ok, fe_reason, fe_ms = check_fe()
    result["fe"] = {"ok": fe_ok, "reason": fe_reason, "ms": round(fe_ms)}
    # ⚠ 실패 시에도 반드시 0 을 발행한다. 지표를 아예 안 보내면 알람이 '데이터 없음' 으로 빠져
    #   설정에 따라 조용히 OK 로 남을 수 있다.
    metrics.append({"MetricName": "FeHealthy", "Value": int(fe_ok), "Unit": "None"})
    metrics.append({"MetricName": "FeLatency", "Value": fe_ms, "Unit": "Milliseconds"})

    for name, path in API_TARGETS.items():
        ok, reason, ms = check_api(path)
        result[name] = {"ok": ok, "reason": reason, "ms": round(ms)}
        dims = [{"Name": "Target", "Value": name}]
        metrics.append(
            {"MetricName": "ApiHealthy", "Dimensions": dims, "Value": int(ok), "Unit": "None"}
        )
        metrics.append(
            {"MetricName": "ApiLatency", "Dimensions": dims, "Value": ms, "Unit": "Milliseconds"}
        )

    print(json.dumps(result, ensure_ascii=False))

    # put_metric_data 는 한 번에 1000개까지 — 대상이 수백 개가 되면 나눠 보내야 한다.
    cloudwatch.put_metric_data(Namespace=NAMESPACE, MetricData=metrics)
    return result
