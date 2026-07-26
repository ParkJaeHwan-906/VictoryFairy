"""S3 게시글 객체 입출력 유틸리티 + 키 규칙.

`run_validation` 러너가 쓰는 S3 접근 계층을 여기에 모은다. 판정 로직은 전혀 없고
(리스팅/읽기/쓰기/키 조립만) 순수 오케스트레이션 헬퍼다.

⚠️ boto3 는 pipeline 전용 의존성이라 로컬 .venv(3.9)엔 없을 수 있다. 이 파일을
`import pipeline.s3_io` 만으로 깨뜨리지 않도록, boto3 는 클라이언트를 실제로 만드는
시점(`build_s3_client`)에서만 지연 import 한다.
"""

import json
from typing import Any, Optional

# 출력 경로의 검열 방식 세그먼트(PIPE-S3IO-12). 예전엔 이 상수 하나로 고정돼 있었으나
# PIPE-2SB-1(two-stage-batch.md) 에 따라 `output_key`/`manifest_key` 의 **호출 인자**
# `method` 로 파라미터화됐다 — 이 상수는 그 인자의 기본값(미지정 시 "pattern")으로만 쓰인다.
# 기존 pattern 단계 호출부(`run_validation.py` 등)는 method 를 넘기지 않으므로 계속
# "pattern" 을 받아 키 문자열이 바이트 동일하게 유지된다(PIPE-2SB-6).
DEFAULT_METHOD = "pattern"

INPUT_PREFIX_TEMPLATE = "community/{source}/{date}/"
OUTPUT_KEY_TEMPLATE = "validation/{method}/{status}/{source}/{date}/{post_id}.json"
# 산출물 리스팅 prefix(PIPE-2SB-4). Bedrock 러너가 pattern 성공분(`validation/pattern/
# success/{source}/{date}/`)을 입력으로 리스팅할 때 쓴다 — output_key 는 post_id 를
# 필수로 받아 단일 키만 조립하므로, post_id 없이 폴더 전체를 리스팅해야 하는 이 용도엔
# 쓸 수 없어 별도 템플릿을 둔다.
OUTPUT_PREFIX_TEMPLATE = "validation/{method}/{status}/{source}/{date}/"
# 완결 처리 마커(멱등 skip 판정용, PIPE-S3IO-24/25). success/failed 와 같은 방식
# 세그먼트 아래 별도 `_manifest` 폴더를 둬 산출물 리스팅과 섞이지 않게 한다.
MANIFEST_KEY_TEMPLATE = "validation/{method}/_manifest/{source}/{date}/{post_id}.json"
# shadow 모드 전용 키(PIPE-2SB-70). success/failed/`_manifest`와 분리된 네임스페이스에만
# 쓴다 — 마커가 없으므로 다음 정식 실행이 이 게시글을 그대로 다시 처리한다.
SHADOW_KEY_TEMPLATE = "validation/{method}/_shadow/{source}/{date}/{post_id}.json"
# 백필 진행 커서 키(PIPE-BF-9). `validation/**` 산출물 키스페이스 **밖**에 둔다 — 커서는
# 산출물이 아니라 제어 상태라 `validation/bedrock/success/` 등 리스팅과 섞이면 안 된다
# (PIPE-2SB-3b 와 같은 취지). 쓰기 주체는 백필 오케스트레이터뿐이다(PIPE-BF-11) —
# 단계 러너(run_validation·run_bedrock)는 이 키를 전혀 참조하지 않는다.
CURSOR_KEY_TEMPLATE = "_backfill/{run_id}/cursor.json"


def input_prefix(source: str, date: str) -> str:
    """게시글 입력 prefix를 조립한다: community/{source}/{date}/"""
    return INPUT_PREFIX_TEMPLATE.format(source=source, date=date)


def output_key(status: str, source: str, date: str, post_id: str, method: str = DEFAULT_METHOD) -> str:
    """성공/실패 산출물 키를 조립한다: validation/{method}/{status}/{source}/{date}/{post_id}.json

    `method`(PIPE-2SB-1)는 "pattern" | "bedrock" 이며 미지정 시 "pattern"(기존 동작과 동일).
    """
    return OUTPUT_KEY_TEMPLATE.format(method=method, status=status, source=source, date=date, post_id=post_id)


def manifest_key(source: str, date: str, post_id: str, method: str = DEFAULT_METHOD) -> str:
    """완결 처리 마커 키를 조립한다.

    `method`(PIPE-2SB-1)는 "pattern" | "bedrock" 이며 미지정 시 "pattern"(기존 동작과 동일).
    """
    return MANIFEST_KEY_TEMPLATE.format(method=method, source=source, date=date, post_id=post_id)


def output_prefix(status: str, source: str, date: str, method: str = DEFAULT_METHOD) -> str:
    """산출물 리스팅 prefix를 조립한다: validation/{method}/{status}/{source}/{date}/

    Bedrock 러너의 입력 prefix(PIPE-2SB-4, `validation/pattern/success/{source}/{date}/`)를
    조립하는 데 쓴다. `output_key`와 세그먼트 순서가 같아야 하므로(PIPE-2SB-6 대칭) 같은
    템플릿 구조를 공유한다.
    """
    return OUTPUT_PREFIX_TEMPLATE.format(method=method, status=status, source=source, date=date)


def shadow_key(source: str, date: str, post_id: str, method: str = DEFAULT_METHOD) -> str:
    """shadow 모드 전용 키를 조립한다(PIPE-2SB-70): validation/{method}/_shadow/{source}/{date}/{post_id}.json

    success/failed/`_manifest`와 분리된 네임스페이스라 산출물 리스팅(PIPE-2SB-3b)과 섞이지
    않는다. `method`는 항상 "bedrock"으로 호출될 것으로 예상되지만(pattern 단계엔 shadow
    개념이 없다) 다른 키 함수와의 일관성을 위해 인자로 열어 둔다.
    """
    return SHADOW_KEY_TEMPLATE.format(method=method, source=source, date=date, post_id=post_id)


def cursor_key(run_id: str) -> str:
    """백필 진행 커서 키를 조립한다(PIPE-BF-9): `_backfill/{runId}/cursor.json`

    `run_id`만 받는다 — 커서는 날짜·소스별로 나뉘지 않고 백필 실행(run) 전체에 하나다
    (PIPE-BF-10: `lastCompletedDate`가 그 run의 날짜 진행 상태를 담는다).
    """
    return CURSOR_KEY_TEMPLATE.format(run_id=run_id)


def build_s3_client(region_name: Optional[str] = None, endpoint_url: Optional[str] = None):
    """boto3 S3 클라이언트를 생성한다.

    - 자격증명은 boto3 기본 체인(환경변수 등)에서 획득한다(PIPE-S3IO-14).
    - 리전은 인자 우선, 없으면 pipeline 설정의 AWS_REGION(기본 ap-northeast-2).
    - 엔드포인트는 인자 우선, 없으면 설정의 S3_ENDPOINT_URL. 둘 다 비어 있으면
      None 을 넘겨 boto3 기본 AWS 리전 엔드포인트를 쓴다(VPC 엔드포인트·MinIO 등
      S3 호환 스토리지에 붙일 때만 env S3_ENDPOINT_URL 로 지정).
    - 테스트에서 페이크/스텁 클라이언트로 교체할 수 있도록 이 함수 자체를
      모킹하거나, 아래 리스팅/읽기/쓰기 함수에 클라이언트를 직접 주입해 쓸 수 있다.
    """
    import boto3  # 지연 import: 로컬 3.9 venv 에 boto3 미설치 상태에서도 파일 import 는 깨지지 않게 한다.

    from pipeline.core.config import pipeline_settings

    return boto3.client(
        "s3",
        region_name=region_name or pipeline_settings.AWS_REGION,
        # 빈 문자열("")도 미설정으로 취급 → None 이면 boto3 가 기본 엔드포인트를 쓴다.
        endpoint_url=endpoint_url or pipeline_settings.S3_ENDPOINT_URL or None,
    )


def list_json_keys(client, bucket: str, prefix: str) -> list[str]:
    """prefix 하위 모든 `.json` 객체 키를 페이지네이션으로 전부 리스팅한다(PIPE-S3IO-27)."""
    keys: list[str] = []
    continuation_token = None
    while True:
        kwargs: dict[str, Any] = {"Bucket": bucket, "Prefix": prefix}
        if continuation_token:
            kwargs["ContinuationToken"] = continuation_token
        response = client.list_objects_v2(**kwargs)
        for obj in response.get("Contents", []):
            key = obj["Key"]
            if key.endswith(".json"):
                keys.append(key)
        if response.get("IsTruncated"):
            continuation_token = response.get("NextContinuationToken")
        else:
            break
    return keys


def get_object_bytes(client, bucket: str, key: str) -> bytes:
    """객체 원본 바이트를 읽는다. S3 접근 실패(권한·네트워크 등)는 그대로 전파한다(PIPE-S3IO-26)."""
    obj = client.get_object(Bucket=bucket, Key=key)
    return obj["Body"].read()


def put_json_object(client, bucket: str, key: str, data: dict) -> None:
    """dict 를 JSON 으로 직렬화해 객체로 쓴다."""
    payload = json.dumps(data, ensure_ascii=False, indent=2).encode("utf-8")
    client.put_object(Bucket=bucket, Key=key, Body=payload, ContentType="application/json")


def delete_object_if_exists(client, bucket: str, key: str) -> None:
    """키를 삭제한다. S3 DeleteObject 는 대상이 없어도 에러 없이 성공 처리되므로
    존재 여부를 먼저 확인하지 않는다(이전 실행의 부분 산출물 정리용, PIPE-S3IO-25)."""
    client.delete_object(Bucket=bucket, Key=key)


def object_exists(client, bucket: str, key: str) -> bool:
    """HeadObject 로 키 존재 여부를 확인한다. 404 계열은 False, 그 외 에러는 그대로 전파한다."""
    from botocore.exceptions import ClientError  # 지연 import: boto3 의존

    try:
        client.head_object(Bucket=bucket, Key=key)
        return True
    except ClientError as exc:
        error_code = exc.response.get("Error", {}).get("Code", "")
        if error_code in ("404", "NoSuchKey", "NotFound"):
            return False
        raise
