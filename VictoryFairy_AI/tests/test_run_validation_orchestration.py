"""pipeline.run_validation 의 오케스트레이션 주변부 — S3 실호출 없이도 검증 가능한 조각들.

pytest 없이 stdlib 로만 실행:  `.venv/bin/python3 tests/test_run_validation_orchestration.py`
(pydantic/pydantic-settings 필요 — .venv 로 실행할 것)

`main()` 자체(전체 오케스트레이션 루프)는 today_kst() 하드코딩 + build_s3_client() 내부
생성 + 실 prefix 조합이 얽혀 있어 이 stdlib 방식으로는 안전하게 목킹하기 어렵다
(요청받은 범위 밖 — 최종 보고서에 미커버로 명시). 여기서는 그와 별개로 **의존성 주입이
가능하거나 순수한 조각들**만 독립적으로 검증한다:
  - PIPE-S3IO-4: today_kst() 형식/타임존 계약.
  - PIPE-S3IO-5: 리전 기본값 ap-northeast-2.
  - PIPE-S3IO-6: SOURCES 가 dcinside·fmkorea 둘 다 포함(구조 확인, 실행 확인 아님).
  - PIPE-S3IO-16: boto3 가 pipeline/requirements.txt·Dockerfile 에 명시됨(파일 내용 확인).
  - PIPE-S3IO-25: _finalize_post 가 client 를 인자로 받으므로 페이크 클라이언트로
    success/failed/marker 쓰기·삭제 분기와 "마커를 마지막에 쓴다"는 순서를 검증.
  - PIPE-S3IO-26: S3 쓰기 실패 시 _die 를 거쳐 0 이 아닌 종료 코드로 중단하는지.
"""

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from pipeline.core.config import pipeline_settings  # noqa: E402
from pipeline.run_validation import SOURCES, _finalize_post, today_kst  # noqa: E402
from pipeline.s3_io import manifest_key, output_key  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]


class FakeS3Client:
    """put_object/delete_object 만 구현한 최소 스텁 — _finalize_post 가 실제로
    부르는 메서드만 흉내 낸다. 호출 순서(calls)를 기록해 "마커를 마지막에 쓴다"는
    원자성 순서(PIPE-S3IO-25)를 검증할 수 있게 한다."""

    def __init__(self, fail_on: "set | None" = None):
        self.store: dict = {}
        self.calls: list = []
        self.fail_on = fail_on or set()

    def put_object(self, Bucket, Key, Body, ContentType=None):
        if Key in self.fail_on:
            raise RuntimeError(f"simulated S3 failure on put {Key}")
        self.store[Key] = Body
        self.calls.append(("put", Key))

    def delete_object(self, Bucket, Key):
        self.store.pop(Key, None)
        self.calls.append(("delete", Key))


def test_today_kst_format_and_timezone():
    # PIPE-S3IO-4: YYYY-MM-DD, KST(Asia/Seoul) 기준.
    from datetime import datetime
    from zoneinfo import ZoneInfo

    value = today_kst()
    assert re.fullmatch(r"\d{4}-\d{2}-\d{2}", value), value
    expected = datetime.now(ZoneInfo("Asia/Seoul")).strftime("%Y-%m-%d")
    assert value == expected


def test_default_region_is_ap_northeast_2():
    # PIPE-S3IO-5
    assert pipeline_settings.AWS_REGION == "ap-northeast-2"


def test_sources_include_both_communities():
    # PIPE-S3IO-6 (구조 확인 — 실제 두 소스 처리 자체는 main() 오케스트레이션이라 미커버)
    assert set(SOURCES) == {"dcinside", "fmkorea"}


def test_boto3_declared_in_pipeline_requirements_and_dockerfile():
    # PIPE-S3IO-16
    requirements = (ROOT / "pipeline" / "requirements.txt").read_text(encoding="utf-8")
    dockerfile = (ROOT / "pipeline" / "Dockerfile").read_text(encoding="utf-8")
    assert re.search(r"^boto3==", requirements, re.MULTILINE), "requirements.txt 에 boto3 없음"
    assert "boto3" in dockerfile, "Dockerfile 에 boto3 언급 없음(requirements.txt 경유 설치 확인 필요)"


def test_finalize_post_writes_marker_last_on_success():
    client = FakeS3Client()
    success_obj = {"postExternalId": "1", "body": "ok", "topComments": []}
    _finalize_post(client, "bucket", "dcinside", "2026-07-22", "1", success_obj, [])

    success_key = output_key("success", "dcinside", "2026-07-22", "1")
    failed_key = output_key("failed", "dcinside", "2026-07-22", "1")
    marker_key = manifest_key("dcinside", "2026-07-22", "1")

    assert success_key in client.store
    assert failed_key not in client.store  # failed_reasons 없음 -> 쓰지 않음(삭제만 시도)
    assert marker_key in client.store
    assert client.calls[-1] == ("put", marker_key), client.calls


def test_finalize_post_deletes_success_when_none_and_writes_failed():
    client = FakeS3Client()
    failed_reasons = [{"unit": "body", "commentIndex": None, "author": None, "text": "x", "message": "m"}]
    _finalize_post(client, "bucket", "dcinside", "2026-07-22", "2", None, failed_reasons)

    success_key = output_key("success", "dcinside", "2026-07-22", "2")
    failed_key = output_key("failed", "dcinside", "2026-07-22", "2")
    marker_key = manifest_key("dcinside", "2026-07-22", "2")

    assert success_key not in client.store
    assert failed_key in client.store
    assert marker_key in client.store
    assert client.calls[-1] == ("put", marker_key), client.calls
    # delete_object_if_exists 는 존재 확인 없이 무조건 delete_object 를 부른다.
    assert ("delete", success_key) in client.calls


def test_finalize_post_dies_with_nonzero_exit_on_s3_write_failure():
    # PIPE-S3IO-26: S3 쓰기 실패 -> 명확한 에러 + 0이 아닌 종료 코드.
    success_key = output_key("success", "dcinside", "2026-07-22", "3")
    client = FakeS3Client(fail_on={success_key})
    success_obj = {"postExternalId": "3", "body": "ok", "topComments": []}

    try:
        _finalize_post(client, "bucket", "dcinside", "2026-07-22", "3", success_obj, [])
        raised = False
        code = None
    except SystemExit as exc:
        raised = True
        code = exc.code

    assert raised, "_finalize_post 가 S3 쓰기 실패에도 SystemExit 를 던지지 않음"
    assert code not in (0, None), f"종료 코드가 0/None: {code}"


if __name__ == "__main__":
    tests = [fn for name, fn in sorted(globals().items())
              if name.startswith("test_") and callable(fn)]
    failed_count = 0
    for fn in tests:
        try:
            fn()
            print(f"PASS  {fn.__name__}")
        except AssertionError as exc:
            failed_count += 1
            print(f"FAIL  {fn.__name__}: {exc}")
    print(f"\n{len(tests) - failed_count}/{len(tests)} passed")
    sys.exit(1 if failed_count else 0)
