"""S3 게시글 객체 입출력 유틸리티 + 키 규칙.

`run_validation` 러너가 쓰는 S3 접근 계층을 여기에 모은다. 판정 로직은 전혀 없고
(리스팅/읽기/쓰기/키 조립만) 순수 오케스트레이션 헬퍼다.

⚠️ boto3 는 pipeline 전용 의존성이라 로컬 .venv(3.9)엔 없을 수 있다. 이 파일을
`import pipeline.s3_io` 만으로 깨뜨리지 않도록, boto3 는 클라이언트를 실제로 만드는
시점(`build_s3_client`)에서만 지연 import 한다.
"""

import json
from typing import Any, Optional

# 출력 경로의 검열 방식 세그먼트(PIPE-S3IO-12). 이번 이터레이션은 패턴(룰/정규식)
# 검열만 다루지만, 향후 "bedrock" 등으로 확장할 때 이 상수/템플릿만 바꾸면 된다.
METHOD = "pattern"

INPUT_PREFIX_TEMPLATE = "community/{source}/{date}/"
OUTPUT_KEY_TEMPLATE = "validation/{method}/{status}/{source}/{date}/{post_id}.json"
# 완결 처리 마커(멱등 skip 판정용, PIPE-S3IO-24/25). success/failed 와 같은 방식
# 세그먼트 아래 별도 `_manifest` 폴더를 둬 산출물 리스팅과 섞이지 않게 한다.
MANIFEST_KEY_TEMPLATE = "validation/{method}/_manifest/{source}/{date}/{post_id}.json"


def input_prefix(source: str, date: str) -> str:
    """게시글 입력 prefix를 조립한다: community/{source}/{date}/"""
    return INPUT_PREFIX_TEMPLATE.format(source=source, date=date)


def output_key(status: str, source: str, date: str, post_id: str) -> str:
    """성공/실패 산출물 키를 조립한다: validation/pattern/{status}/{source}/{date}/{post_id}.json"""
    return OUTPUT_KEY_TEMPLATE.format(method=METHOD, status=status, source=source, date=date, post_id=post_id)


def manifest_key(source: str, date: str, post_id: str) -> str:
    """완결 처리 마커 키를 조립한다."""
    return MANIFEST_KEY_TEMPLATE.format(method=METHOD, source=source, date=date, post_id=post_id)


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
