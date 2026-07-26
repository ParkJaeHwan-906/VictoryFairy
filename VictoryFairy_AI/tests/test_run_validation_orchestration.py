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


def test_batch_date_overrides_today_kst_with_fallback():
    """PIPE-2SB-37/38 — 주입된 배치 시작일이 today_kst() 를 이긴다.

    이게 없으면 배치가 자정을 넘길 때 대상 prefix 가 갈린다. Spot 회수로 러너가 00:30 에
    재기동되면 today_kst() 가 다음 날로 넘어가 **전날 미처리분이 든 prefix 를 영영 보지
    않는다** — 마커도 Redis 도 되살리지 못한다(둘 다 날짜 prefix 안에서만 의미가 있다).

    main() 은 S3 클라이언트를 만들어 오케스트레이션을 돌리므로 그대로 부를 수 없다.
    main() 이 쓰는 것과 같은 결정식을 여기서 검증한다.
    """
    from pipeline.run_validation import PatternRunnerSettings

    def resolve(settings):  # run_validation.main() 의 날짜 결정식과 동일해야 한다.
        return (settings.BATCH_DATE or "").strip() or today_kst()

    assert resolve(PatternRunnerSettings(BATCH_DATE="2026-07-09")) == "2026-07-09"
    # 미주입·빈 문자열·공백은 모두 폴백(PIPE-2SB-38) — 로컬 수동 실행 편의.
    assert resolve(PatternRunnerSettings(BATCH_DATE=None)) == today_kst()
    assert resolve(PatternRunnerSettings(BATCH_DATE="   ")) == today_kst()


def test_default_region_is_ap_northeast_2():
    # PIPE-S3IO-5
    assert pipeline_settings.AWS_REGION == "ap-northeast-2"


def test_sources_include_both_communities():
    # PIPE-S3IO-6 (구조 확인 — 실제 두 소스 처리 자체는 main() 오케스트레이션이라 미커버)
    assert set(SOURCES) == {"dcinside", "fmkorea"}


def test_boto3_declared_in_pipeline_requirements_and_dockerfile():
    """PIPE-S3IO-16 — boto3 가 배치 이미지에 실제로 설치되는가.

    ⚠️ 예전에는 Dockerfile 에 `"boto3" in dockerfile` 문자열이 있는지만 봤는데, 그건
    **주석에 boto3 를 언급했다는 사실**에 의존하는 검사였다(실제로 주석을 손대자
    깨졌다). 설치 경로는 requirements.txt 경유이므로 **그 사슬을 직접 확인**한다.
    """
    requirements = (ROOT / "pipeline" / "requirements.txt").read_text(encoding="utf-8")
    dockerfile = (ROOT / "pipeline" / "Dockerfile").read_text(encoding="utf-8")
    assert re.search(r"^boto3==", requirements, re.MULTILINE), "requirements.txt 에 boto3 없음"
    # 경로 앞에 ${LAMBDA_TASK_ROOT} 같은 접두사가 붙을 수 있으므로 파일명 기준으로 본다.
    assert re.search(r"pip install[^\n]*-r\s+\S*pipeline/requirements\.txt", dockerfile), (
        "Dockerfile 이 pipeline/requirements.txt 를 설치하지 않는다 — boto3 가 이미지에 안 들어간다"
    )


def test_batch_image_excludes_analysis_stack():
    """PIPE-S3IO-40 — 배치 이미지에 analysis 계열이 들어가면 안 된다.

    배치 흐름은 run_validation → run_bedrock 2단계뿐인데(PIPE-S3IO-28) 이미지가
    torch 와 KoELECTRA NER 모델까지 담아 **1.63GB** 였다. 전부 쓰지 않는 무게이고,
    Spot 노드는 뜰 때마다 이미지를 pull 하므로 기동 시간에 그대로 붙는다.
    분리 후 274MB (83% 감소).

    analysis 가 배선에 복귀하면 이 테스트와 PIPE-S3IO-40 을 함께 재검토한다.
    """
    requirements = (ROOT / "pipeline" / "requirements.txt").read_text(encoding="utf-8")
    dockerfile = (ROOT / "pipeline" / "Dockerfile").read_text(encoding="utf-8")

    for package in ("kiwipiepy", "transformers", "torch"):
        assert not re.search(rf"^{package}==", requirements, re.MULTILINE), (
            f"pipeline/requirements.txt 에 {package} 가 있다 — 배치가 쓰지 않는 의존성이다(PIPE-S3IO-40)"
        )

    # COPY 대상에 analysis 가 없어야 한다. 주석의 'analysis' 언급은 무해하므로 COPY 행만 본다.
    copied = set(re.findall(r"^COPY\s+(\w+)/\s", dockerfile, re.MULTILINE))
    assert "analysis" not in copied, "Dockerfile 이 analysis/ 를 COPY 한다(PIPE-S3IO-40)"
    assert {"validation", "bedrock", "pipeline"} <= copied, (
        f"러너가 import 하는 모듈이 빠졌다 — COPY 된 것: {sorted(copied)}"
    )

    # ⚠️ Lambda 함수의 architectures 와 이미지 아키텍처가 다르면 함수 생성이 실패한다.
    # 빌드 장소(맥=arm64 / CI=amd64)에 따라 결과가 갈리므로 Dockerfile 에 고정한다.
    assert re.search(r"^FROM\s+--platform=linux/arm64\s", dockerfile, re.MULTILINE), (
        "베이스 이미지에 --platform 이 고정돼 있지 않다 — 빌드 장소에 따라 아키텍처가 갈려 "
        "Terraform 의 architectures 와 어긋나면 Lambda 생성이 실패한다"
    )
    assert re.search(r"^FROM[^\n]*public\.ecr\.aws/lambda/python", dockerfile, re.MULTILINE), (
        "Lambda 베이스 이미지가 아니다 — 일반 python 이미지는 Runtime Interface Client 가 없어 "
        "함수가 뜨자마자 죽는다"
    )

    # NER 모델 프리캐시·torch 설치는 analysis 전용이다.
    # ⚠️ 주석이 아니라 **실행되는 명령**만 본다 — 위 boto3 테스트가 주석 문자열에
    # 의존하다 깨졌던 것과 같은 실수를 반복하지 않기 위해서다(이 주석 자체에도
    # KoELECTRA·torch 라는 낱말이 들어 있다).
    run_lines = "\n".join(
        line for line in dockerfile.splitlines() if line.strip().startswith(("RUN", "COPY", "ENV"))
    )
    assert "KoELECTRA" not in run_lines, "배치 이미지가 NER 모델을 캐시한다(PIPE-S3IO-40)"
    assert "torch" not in run_lines, "배치 이미지가 torch 를 설치한다(PIPE-S3IO-40)"


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
