"""Bedrock 누적 소비액 카운터 — DynamoDB 백엔드.

구 설계는 배치 전용 Redis 의 `INCRBYFLOAT` 였다. 정제 파이프라인이 EKS 배치에서
**Lambda 서버리스로 옮겨가면서** 그 저장소를 쓸 수 없게 됐다 — Lambda 는 VPC 밖이라
클러스터 안의 ClusterIP Redis 에 도달하지 못한다. DynamoDB 의 `UpdateItem ... ADD` 가
같은 원자적 증분을 준다.

## 왜 파티션 키가 날짜인가

`batch_date` 하나를 파티션 키로 두면 **일 단위 리셋이 구조적으로 성립한다** — 날짜가
바뀌면 다른 아이템이라 "어제 쓴 돈"이 오늘 상한에 섞이지 않는다. 별도의 초기화 작업이
없다(구 `emptyDir` Redis 가 배치마다 새로 뜨며 주던 성질과 같다).

## 실패 취급 — 이 모듈의 핵심 계약

접근 실패는 **올린다**(`BedrockCostCounterUnavailable`). 0.0 으로 눙기면 배치 전
예산 확인이 항상 통과해 상한이 조용히 사라진다. 호출부가 반드시 이어받아 중단해야 한다.

예외는 **실과금이 0인 모드**(`BEDROCK_DRY_RUN`)뿐이다 — `required=False` 로 만들면
관대하게 0.0 을 돌려준다. ⚠️ `BEDROCK_SHADOW` 는 면제 대상이 **아니다**. 실제로 모델을
호출하므로 돈이 나간다.

## 비용

서울 리전 온디맨드 실요율(AWS 공개 가격 파일 확인, 2026-07-26):
    쓰기 $0.68 / 100만 WRU · 읽기 $0.1355 / 100만 RRU · 스토리지 첫 25GB 무료

호출 1회(SQS 배치 = 게시글 5건)당 GetItem 1 + UpdateItem 1 이라, 목표 규모
(게시글 1만/일 → 호출 1,210/일)에서도 **월 $0.03** 이다. 같은 규모의 Bedrock 모델
호출이 하루 $13.79 이므로 카운터 비용은 그 0.03% 수준이다.

TTL 은 30일이다. 스토리지가 무료라 지워서 아낄 것이 없고, **추정 소비액과 Cost Explorer
실청구액을 대조할 이력**이 필요하다(ARCHITECTURE 운영 절차). ⚠️ TTL 을 처리 창보다
짧게 잡으면 **하루 중간에 상한이 리셋된다** — 크롤이 10분 주기 상시라 처리가 하루 종일
이어진다. 그리고 DynamoDB TTL 삭제는 만료 후 최대 48시간 이내의 best-effort 라
"N일 TTL = 정확히 N일 뒤 삭제"가 아니다.
"""

import sys
from typing import Optional

# 하루가 지나도 그날 처리가 이어질 수 있으므로 넉넉히 둔다. 스토리지가 무료라
# 늘려도 비용이 없고, 줄이면 상한 리셋 위험만 생긴다.
DEFAULT_TTL_DAYS = 30


class BedrockCostCounterUnavailable(Exception):
    """누적 소비액 카운터에 접근할 수 없다(조회/누적 실패 또는 미연결).

    ⚠️ 이 예외를 로그로 삼키면 안 된다. 삼키는 순간 배치 전 예산 확인이 항상
    통과해 상한이 무의미해진다. 호출부가 명확한 에러 + 0이 아닌 종료 코드로 중단한다.
    """


class DynamoDBSpendCounter:
    """`batch_date` 별 누적 소비액을 DynamoDB 로 관리한다.

    인터페이스는 구 `BatchRedis` 와 같다(`get_spend`/`add_spend`/`srem_pending`) —
    `run_bedrock.main()` 의 `cost_tracker` 주입 지점을 그대로 쓰기 위해서다.

    ⚠️ `srem_pending` 은 **의도적으로 no-op** 이다. 작업 집합(`pending:*`)은 Lambda
    전환으로 폐기됐다 — 단계 전이를 S3 이벤트와 SQS 가 담당하므로 우리가 셀 대상이 없다.
    """

    def __init__(
        self,
        table_name: Optional[str],
        batch_date: str,
        ttl_days: int = DEFAULT_TTL_DAYS,
        required: bool = True,
        client=None,
    ):
        self._table_name = table_name
        self._batch_date = batch_date
        self._ttl_days = ttl_days
        self._required = required
        self._client = client

        if client is not None:
            return
        if not table_name:
            self._warn(
                "BUDGET_TABLE_NAME 미설정 — 누적 소비액을 추적할 수 없습니다."
            )
            return
        try:
            import boto3  # 지연 import: 로컬에 boto3 가 없어도 다른 러너 import 는 깨지지 않는다.

            self._client = boto3.client("dynamodb")
        except Exception as exc:  # noqa: BLE001
            self._warn(f"DynamoDB 클라이언트 생성 실패: {exc}")
            self._client = None

    def _warn(self, message: str) -> None:
        note = "" if self._required else " (실과금 0인 모드라 카운터는 면제됨)"
        print(f"경고: {message}{note}", file=sys.stderr)

    def _unavailable(self, message: str, cause: Optional[Exception] = None):
        if not self._required:
            return None
        exc = BedrockCostCounterUnavailable(message)
        if cause is not None:
            raise exc from cause
        raise exc

    def get_spend(self) -> float:
        """오늘(`batch_date`)까지 누적된 소비액(USD)."""
        if self._client is None:
            self._unavailable("DynamoDB 에 연결되어 있지 않아 누적 소비액을 확인할 수 없습니다.")
            return 0.0
        try:
            resp = self._client.get_item(
                TableName=self._table_name,
                Key={"batch_date": {"S": self._batch_date}},
                # 강한 일관성 — 직전 호출의 누적분을 못 읽으면 상한을 넘겨 놓고 뒤늦게 안다.
                ConsistentRead=True,
                ProjectionExpression="spend_usd",
            )
        except Exception as exc:  # noqa: BLE001
            self._unavailable(f"누적 소비액 조회 실패({self._batch_date}): {exc}", exc)
            return 0.0

        item = resp.get("Item") or {}
        raw = item.get("spend_usd", {}).get("N")
        return float(raw) if raw is not None else 0.0

    def add_spend(self, amount_usd: float) -> None:
        """이번 호출분을 누적한다.

        `ADD` 는 원자적이라 동시에 여러 실행이 더해도 유실되지 않는다. 다만 그것만으로는
        **상한을 넘기는 것을 막지 못한다** — 각자 "아직 여유 있음"을 읽고 함께 넘길 수
        있다. 그래서 인프라에서 Bedrock Lambda 예약 동시성을 1로 묶어 직렬화한다.
        """
        if amount_usd <= 0:
            return
        if self._client is None:
            self._unavailable("DynamoDB 에 연결되어 있지 않아 누적 소비액을 기록할 수 없습니다.")
            return
        try:
            self._client.update_item(
                TableName=self._table_name,
                Key={"batch_date": {"S": self._batch_date}},
                # spend_usd 는 ADD 로 증분, expires_at 은 없을 때만 채운다
                # (매번 갱신하면 처리가 이어지는 동안 만료가 계속 밀린다 — 의도치 않은 무기한 보관).
                UpdateExpression=(
                    "ADD spend_usd :amount "
                    "SET expires_at = if_not_exists(expires_at, :expires)"
                ),
                ExpressionAttributeValues={
                    ":amount": {"N": str(amount_usd)},
                    ":expires": {"N": str(self._expires_at())},
                },
            )
        except Exception as exc:  # noqa: BLE001
            self._unavailable(f"누적 소비액 기록 실패({self._batch_date}): {exc}", exc)

    def _expires_at(self) -> int:
        """`batch_date` 자정(KST) + TTL 일수를 epoch seconds 로."""
        from datetime import datetime, timedelta
        from zoneinfo import ZoneInfo

        day = datetime.strptime(self._batch_date, "%Y-%m-%d").replace(
            tzinfo=ZoneInfo("Asia/Seoul")
        )
        return int((day + timedelta(days=self._ttl_days)).timestamp())

    def srem_pending(self, member: str) -> None:
        """no-op. 작업 집합은 Lambda 전환으로 폐기됐다(모듈 docstring 참고)."""
