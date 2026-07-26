"""누적 데이터 백필 오케스트레이터 — 과거 날짜 범위를 순회하며 패턴·Bedrock 두 단계를
`BATCH_DATE`처럼 하루씩 넘겨 그대로 재사용한다.

`docs/requirements/pipeline/backfill.md`(PIPE-BF-*) 승인 계약의 구현체다. 선행 계약
`docs/requirements/pipeline/s3-io.md`(v2)·`docs/requirements/pipeline/two-stage-batch.md`
(PIPE-2SB-*)를 **재정의하지 않는다** — 같은 키 규약·같은 판정 로직·같은 멱등 마커를 그대로
쓴다(PIPE-BF-3). 이 파일이 새로 갖는 것은 **날짜 순회·진행 커서·백필 전용 예산**뿐이다.

## 판정 로직은 여기 없다
패턴 단계는 `run_validation.process_post`/`run_validation._finalize_post`를 그대로
import해 쓴다(재구현 금지). Bedrock 단계는 `run_bedrock.main()`을 `date`·`cost_tracker`·
`spend_limit_usd` 인자로 호출한다 — 판정은 여전히 `bedrock.judge_service` 소관이고, 이
오케스트레이터는 그 결과를 보고 날짜 순회·커서 전진만 결정한다.

## 예산 누적처가 다르다(PIPE-BF-15)
`run_bedrock`의 정규 경로는 공유 Redis `bedrock_spend_usd`에 누적하고 실패 시 중단한다.
백필은 그 카운터를 **읽지도 쓰지도 않는다** — 대신 이 파일의 `Cursor`가 `_backfill/{runId}/
cursor.json`의 `spendUsd`에 누적한다(S3, 여러 밤에 걸쳐 살아남아야 하므로 — `batch-redis`는
`emptyDir`라 배치와 함께 사라진다). `Cursor`는 `BatchRedis`와 같은 인터페이스
(`get_spend`/`add_spend`/`srem_pending`)를 duck-typing으로 구현해 `run_bedrock.main()`에
그대로 주입된다. `srem_pending`은 no-op이라 PIPE-2SB-72의 `SREM`(`pending:bedrock`)이
백필 모드에서는 전혀 호출되지 않는다(PIPE-BF-3b). `SADD`(PIPE-2SB-47)는 애초에
`run_validation.py`에 구현돼 있지 않으므로(아래 "완료 후" 보고 참고) 별도 조치가 필요 없다.

## 날짜 순서(PIPE-BF-7/8/12)
`BACKFILL_FROM`~`BACKFILL_TO`를 오름차순으로 순회한다. 각 날짜에 대해 **패턴 완결 →
Bedrock** 순서로 두 단계를 마친 뒤에만 커서의 `lastCompletedDate`를 전진시킨다(PIPE-BF-12
— 반대 순서면 마커 없는 날짜를 커서가 앞질러 영구 미처리가 된다). 입력이 없는 날짜(예:
2026-07-13)는 두 단계 모두 "처리할 것 없음"으로 자연히 종료되어 건너뛴 것과 같은 효과를
낸다(PIPE-BF-5 — 별도 분기 없이 `_collect_pending`/입력 리스팅이 빈 리스트를 돌려주는 것으로
처리된다).

## 예산 경계가 날짜 경계보다 우선(PIPE-BF-18)
매 날짜를 시작하기 **전에** 누적 소비액을 확인한다. 상한 이상이면 그 날짜(및 이후 모든
날짜)를 시작하지 않고 정상 종료(exit 0, PIPE-BF-19)한다. 이미 시작한 날짜의 Bedrock 단계가
게시글 일부만 처리하고 예산으로 중단됐다면(반환된 `not_started > 0`) 그 날짜도 "완결"로
보지 않는다 — 커서를 전진시키지 않고 종료한다. 다음 실행은 이 날짜를 처음부터 다시 훑되,
이미 마커가 있는 게시글은 스킵되므로(PIPE-BF-6) 재과금은 없다.

## 정책 변경 재적용과의 통합(PIPE-BF-24~27)
이 진입점은 재적용의 실행 수단이기도 하다 — 차이는 마커 존재 여부뿐이다. 마커 삭제는
운영 절차이며 이 러너는 **마커를 스스로 지우지 않는다**(PIPE-BF-25). `BACKFILL_MODE`
(`"backfill"` | `"reapply"`)는 커서에 기록만 될 뿐 동작을 바꾸지 않는다(PIPE-BF-26).

## 이 파일이 다루지 않는 것
- **정규 야간 배치와의 동시 실행 금지(PIPE-BF-20)·동시 백필 Job 금지(PIPE-BF-21)** — 이
  프로세스 자신은 "정규 배치가 지금 실행 중인지"를 판단할 신뢰할 수 있는 신호가 없다
  (그 상태의 정본은 k8s Job 상태다). k8s 매니페스트 작성은 인프라 소관이라는 문서의 명시적
  제외 사항과 같은 이유로, 이 러너는 **아무 가드도 걸지 않는다** — CronJob
  `concurrencyPolicy`/컨트롤러 쪽에서 막아야 한다. 완료 후 보고서에 이 공백을 명시한다.

실행: (프로젝트 루트에서)
    BACKFILL_FROM=2026-07-09 BACKFILL_TO=2026-07-25 python -m pipeline.run_backfill
"""

import json
import sys
from datetime import date as date_cls
from datetime import datetime, timedelta
from typing import Any, Optional
from zoneinfo import ZoneInfo

from pydantic_settings import BaseSettings, SettingsConfigDict

from pipeline import run_bedrock
from pipeline.core.config import pipeline_settings
from pipeline.run_validation import SOURCES, _finalize_post, process_post
from pipeline.s3_io import (
    build_s3_client,
    cursor_key,
    get_object_bytes,
    input_prefix,
    list_json_keys,
    manifest_key,
    object_exists,
    put_json_object,
)


class BackfillSettings(BaseSettings):
    """백필 오케스트레이터 전용 설정(PIPE-BF-2/9/14/26 — env 키명은 "(가정)"에서 확정)."""

    # PIPE-BF-2: 대상 날짜 범위. 코드에 날짜를 하드코딩하지 않는다 — 범위는 항상 env로 받는다.
    BACKFILL_FROM: Optional[str] = None
    BACKFILL_TO: Optional[str] = None

    # PIPE-BF-9: 커서 키 `_backfill/{runId}/cursor.json`의 runId. 재개 시에는 이전 실행과
    # 같은 값을 다시 넘겨야 그 커서를 이어받는다. 비우면 새 run으로 처음부터 시작한다.
    BACKFILL_RUN_ID: Optional[str] = None

    # PIPE-BF-14: 백필 전용 상한. 정규 $30/밤(BEDROCK_SPEND_LIMIT_USD)과 분리된 별도 값이다.
    BACKFILL_BUDGET_USD: float = 80.0

    # PIPE-BF-26: 커서에 기록되는 값. 동작을 바꾸지 않고 사후 구분(비용·지표 분리)에만 쓰인다.
    BACKFILL_MODE: str = "backfill"  # "backfill" | "reapply"

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


backfill_settings = BackfillSettings()


def _die(message: str) -> None:
    """명확한 에러를 남기고 0이 아닌 종료 코드로 중단한다(PIPE-2SB-22 계승)."""
    print(f"오류: {message}", file=sys.stderr)
    sys.exit(1)


def _now_iso() -> str:
    return datetime.now(ZoneInfo("Asia/Seoul")).isoformat()


def _default_run_id() -> str:
    return datetime.now(ZoneInfo("Asia/Seoul")).strftime("%Y%m%dT%H%M%S")


def _iter_dates(date_from: str, date_to: str):
    """`date_from`~`date_to`(양끝 포함)를 오름차순으로 순회한다(PIPE-BF-7)."""
    start = date_cls.fromisoformat(date_from)
    end = date_cls.fromisoformat(date_to)
    current = start
    while current <= end:
        yield current.isoformat()
        current += timedelta(days=1)


class Cursor:
    """진행 커서(PIPE-BF-9/10) — `_backfill/{runId}/cursor.json`.

    쓰기 주체는 이 오케스트레이터뿐이다(PIPE-BF-11) — `run_validation`·`run_bedrock`은
    이 키를 전혀 참조하지 않는다(그 두 파일은 손대지 않았다).

    `BatchRedis`(`pipeline/run_bedrock.py`)와 같은 인터페이스(`get_spend`/`add_spend`/
    `srem_pending`)를 구현해 `run_bedrock.main(cost_tracker=...)`에 그대로 주입된다:
    - `get_spend`/`add_spend`: 정규 `bedrock_spend_usd`(Redis) 대신 이 커서의 `spendUsd`
      (S3)에 누적한다(PIPE-BF-15). 정규 카운터는 읽지도 쓰지도 않는다.
    - `srem_pending`: no-op — PIPE-2SB-72의 `SREM`(`pending:bedrock`)을 백필 모드에서
      수행하지 않는다(PIPE-BF-3b). `SADD`(PIPE-2SB-47)는 애초에 `run_validation.py`에
      구현돼 있지 않아 별도 차단이 필요 없다(완료 후 보고 참고).
    """

    def __init__(self, client, bucket: str, run_id: str, mode: str, date_from: str, date_to: str):
        self._client = client
        self._bucket = bucket
        self._key = cursor_key(run_id)
        self.run_id = run_id
        self.mode = mode
        self.date_from = date_from
        self.date_to = date_to
        self.last_completed_date: Optional[str] = None
        self.spend_usd: float = 0.0
        self._load_or_init()

    def _load_or_init(self) -> None:
        try:
            exists = object_exists(self._client, self._bucket, self._key)
        except Exception as exc:  # noqa: BLE001
            _die(f"S3 접근 실패(커서 확인 {self._key}): {exc}")
            return

        if not exists:
            # 새 run — 초기 상태를 즉시 기록해 둔다(운영자가 시작을 바로 확인할 수 있게).
            self._persist()
            return

        try:
            raw = get_object_bytes(self._client, self._bucket, self._key)
            data = json.loads(raw)
        except Exception as exc:  # noqa: BLE001
            _die(f"커서 읽기 실패({self._key}): {exc}")
            return

        self.last_completed_date = data.get("lastCompletedDate")
        self.spend_usd = float(data.get("spendUsd") or 0.0)
        print(
            f"커서 재개: {self._key} (lastCompletedDate={self.last_completed_date}, "
            f"spendUsd=${self.spend_usd:.4f})"
        )

    def _persist(self) -> None:
        payload = {
            "runId": self.run_id,
            "mode": self.mode,  # PIPE-BF-26: "backfill" | "reapply"
            "from": self.date_from,
            "to": self.date_to,
            "order": "asc",  # PIPE-BF-7
            "lastCompletedDate": self.last_completed_date,
            "spendUsd": self.spend_usd,
            "updatedAt": _now_iso(),
        }
        try:
            put_json_object(self._client, self._bucket, self._key, payload)
        except Exception as exc:  # noqa: BLE001
            _die(f"커서 쓰기 실패({self._key}): {exc}")

    # --- run_bedrock.main(cost_tracker=...)가 기대하는 인터페이스 ------------------

    def get_spend(self) -> float:
        return self.spend_usd

    def add_spend(self, amount_usd: float) -> None:
        """PIPE-BF-15/16: 백필 전용 spendUsd에 누적하고 즉시 커서를 갱신한다.

        갱신 주기는 "배치 호출마다"(PIPE-BF-16 기준값) — PUT 비용은 무시할 수준이고
        (문서 근거: 7,000회 ≈ $0.035), 크래시 시 유실되는 소비 기록을 최소화한다.
        """
        if amount_usd <= 0:
            return
        self.spend_usd += amount_usd
        self._persist()

    def srem_pending(self, member: str) -> None:
        """PIPE-BF-3b: 백필 모드에서는 작업 집합을 건드리지 않는다 — 항상 no-op."""
        return

    # --- 오케스트레이터 전용 ---------------------------------------------------

    def advance_date(self, date: str) -> None:
        """PIPE-BF-12: 그 날짜의 마커 전건 기록이 끝난 뒤에만 호출해야 한다(호출부 책임)."""
        self.last_completed_date = date
        self._persist()


def _run_pattern_stage_for_date(client, bucket: str, date: str) -> dict:
    """패턴(1차 검열) 단계를 이 날짜에 대해 실행한다.

    `run_validation.main()`과 완전히 같은 판정 로직·키 규약·마커를 쓴다(PIPE-BF-3) —
    `process_post()`·`_finalize_post()`를 그대로 재사용한다(재구현하지 않는다). 다른
    점은 `today_kst()` 고정 대신 임의의 `date`를 받는다는 것뿐이다. `run_validation.py`
    자체는 `main()`이 오늘 날짜에 고정돼 있어 날짜를 주입할 수 없으므로(그 파일은 손대지
    않는다는 지시에 따라) 이 반복문만 여기 새로 둔다 — 판정·라우팅·쓰기는 전부 재사용이다.

    반환: {"has_input": bool, "processed": int, "skipped_done": int, "skipped_bad": int}
    """
    total_processed = 0
    total_skipped_done = 0
    total_skipped_bad = 0
    has_input = False

    for source in SOURCES:
        prefix = input_prefix(source, date)

        try:
            keys = list_json_keys(client, bucket, prefix)
        except Exception as exc:  # noqa: BLE001 — PIPE-2SB-22 계승
            _die(f"S3 리스팅 실패({source}, prefix={prefix}): {exc}")
            return {}

        if not keys:
            # PIPE-BF-5: 입력이 없는 날짜/소스는 건너뛰고 계속한다.
            print(f"  [pattern] {source}: {prefix} 에 객체 없음 — 건너뜀")
            continue

        has_input = True
        print(f"  [pattern] {source}: {len(keys)}개 객체 리스팅됨 ({prefix})")

        for key in keys:
            filename_post_id = key.rsplit("/", 1)[-1].removesuffix(".json")
            marker_key = manifest_key(source, date, filename_post_id)  # method="pattern" 기본값

            try:
                already_done = object_exists(client, bucket, marker_key)
            except Exception as exc:  # noqa: BLE001
                _die(f"S3 접근 실패(매니페스트 확인 {key}): {exc}")
                return {}

            if already_done:
                # PIPE-BF-6: 완결 마커가 있으면 건너뛴다(예: 이미 정제된 07-22의 557건).
                total_skipped_done += 1
                continue

            try:
                raw = get_object_bytes(client, bucket, key)
            except Exception as exc:  # noqa: BLE001 — PIPE-2SB-22
                _die(f"S3 접근 실패(객체 읽기 {key}): {exc}")
                return {}

            try:
                post = json.loads(raw)
            except (json.JSONDecodeError, UnicodeDecodeError) as exc:
                # PIPE-2SB-23 계승: 불량 객체는 건너뛰고 계속한다.
                print(f"경고: JSON 파싱 실패({key}): {exc} — 건너뜀")
                total_skipped_bad += 1
                continue

            if not isinstance(post, dict) or not post.get("postExternalId"):
                print(f"경고: 필수 필드(postExternalId) 누락({key}) — 건너뜀")
                total_skipped_bad += 1
                continue

            post_id = str(post["postExternalId"])
            success_obj, failed_reasons = process_post(post)  # 판정 로직 재사용(PIPE-BF-3)
            _finalize_post(client, bucket, source, date, post_id, success_obj, failed_reasons)
            total_processed += 1

    return {
        "has_input": has_input,
        "processed": total_processed,
        "skipped_done": total_skipped_done,
        "skipped_bad": total_skipped_bad,
    }


def main() -> None:
    bucket = pipeline_settings.S3_BUCKET
    if not bucket:
        _die("환경변수 S3_BUCKET 이 설정되지 않았습니다.")
        return

    date_from = backfill_settings.BACKFILL_FROM
    date_to = backfill_settings.BACKFILL_TO
    if not date_from or not date_to:
        _die("환경변수 BACKFILL_FROM·BACKFILL_TO 가 필요합니다(PIPE-BF-2).")
        return

    mode = backfill_settings.BACKFILL_MODE
    if mode not in ("backfill", "reapply"):
        _die(f"BACKFILL_MODE 는 'backfill' 또는 'reapply' 여야 합니다: {mode!r}(PIPE-BF-26).")
        return

    run_id = backfill_settings.BACKFILL_RUN_ID or _default_run_id()
    budget = backfill_settings.BACKFILL_BUDGET_USD  # PIPE-BF-14

    client = build_s3_client()
    cursor = Cursor(client, bucket, run_id, mode, date_from, date_to)

    all_dates = list(_iter_dates(date_from, date_to))
    if cursor.last_completed_date:
        # PIPE-BF-13: 재개 시 lastCompletedDate 다음 날짜부터 처리한다.
        try:
            resume_index = all_dates.index(cursor.last_completed_date) + 1
        except ValueError:
            # 이전 run의 lastCompletedDate가 이번 범위 밖이면(범위를 바꿔 재개한 경우
            # 등) 안전하게 처음부터 다시 훑는다 — 마커가 있으므로 재과금은 없다.
            resume_index = 0
        remaining_dates = all_dates[resume_index:]
    else:
        remaining_dates = all_dates

    print(
        f"백필 시작: runId={run_id} mode={mode} 범위=[{date_from}..{date_to}] "
        f"budget=${budget:.2f} 커서spendUsd=${cursor.get_spend():.4f} 남은날짜수={len(remaining_dates)}"
    )

    exit_reason = "range_completed"
    stopped_at_index = len(remaining_dates)

    for idx, date in enumerate(remaining_dates):
        # PIPE-BF-18: 예산 경계가 날짜 경계보다 우선한다 — 새 날짜를 시작하기 전에 확인한다.
        if cursor.get_spend() >= budget:
            exit_reason = "budget_exhausted"
            stopped_at_index = idx
            break

        print(f"--- 날짜 {date} ({idx + 1}/{len(remaining_dates)}) ---")

        pattern_summary = _run_pattern_stage_for_date(client, bucket, date)
        print(
            f"  [pattern] 완료: 처리 {pattern_summary.get('processed', 0)} / "
            f"이미완결(skip) {pattern_summary.get('skipped_done', 0)} / "
            f"불량객체(skip) {pattern_summary.get('skipped_bad', 0)}"
        )

        # PIPE-BF-8: 이 날짜는 패턴 완결 → Bedrock 순서로 마친다.
        bedrock_summary = run_bedrock.main(
            date=date,
            cost_tracker=cursor,
            spend_limit_usd=budget,
        )
        print(
            f"  [bedrock] 완료: 처리 {bedrock_summary.get('processed', 0)} / "
            f"이미완결(skip) {bedrock_summary.get('skipped_done', 0)} / "
            f"불량객체(skip) {bedrock_summary.get('skipped_bad', 0)} / "
            f"예산으로 미시작 {bedrock_summary.get('not_started', 0)}"
        )

        remaining_after = len(remaining_dates) - idx - 1
        print(
            f"날짜 {date} / 처리 {bedrock_summary.get('processed', 0)} / "
            f"skip(마커) {pattern_summary.get('skipped_done', 0) + bedrock_summary.get('skipped_done', 0)} / "
            f"불량 {pattern_summary.get('skipped_bad', 0) + bedrock_summary.get('skipped_bad', 0)} / "
            f"누적소비액 ${cursor.get_spend():.4f} / 남은날짜 {remaining_after}"
        )  # PIPE-BF-28

        if bedrock_summary.get("not_started", 0) > 0:
            # 이 날짜의 Bedrock 단계가 예산 소진으로 다 끝나지 못했다 — 두 단계가 모두
            # 끝난 게 아니므로 완결로 보지 않는다(PIPE-BF-8/12). 커서를 전진시키지 않고
            # 종료한다. 다음 실행이 이 날짜를 처음부터 다시 훑되, 마커 skip 덕에
            # LLM 비용은 0이다(PIPE-BF-6/13).
            exit_reason = "budget_exhausted"
            stopped_at_index = idx
            break

        # PIPE-BF-12: 이 날짜의 마커 전건 기록이 끝난 뒤에만 커서를 전진시킨다.
        cursor.advance_date(date)

    unprocessed_dates = remaining_dates[stopped_at_index:]
    print(
        f"백필 종료: 사유={exit_reason} runId={run_id} lastCompletedDate={cursor.last_completed_date} "
        f"누적소비액=${cursor.get_spend():.4f} 미처리날짜수={len(unprocessed_dates)}"
    )  # PIPE-BF-29


if __name__ == "__main__":
    main()
