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

ASSET_RE = re.compile(r"assets/index-[A-Za-z0-9]+\.js")

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


def check_fe():
    """FE 가 실제로 동작하는지. (정상여부, 사유, 소요ms)."""
    status, body, ms = _get("/")
    if status != 200:
        return False, f"/ 가 {status}", ms

    # 진입점이 가리키는 번들을 실제로 받아본다. 이 확인이 핵심이다 —
    # index.html 만 200 이면 자산이 사라져도 통과해버려 점검이 무의미해진다.
    m = ASSET_RE.search(body)
    if not m:
        return False, "index.html 에서 진입 번들 참조를 찾지 못했다", ms

    asset = m.group(0)
    status, _, _ = _get(f"/{asset}")
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
