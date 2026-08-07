import json

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError

from . import keys

_NOT_FOUND = {"404", "NoSuchKey", "NotFound"}


class S3RawSink:
    def __init__(self, settings):
        cfg = Config(
            s3={"addressing_style": "path" if settings.s3_path_style else "auto"}
        )
        kwargs = {"region_name": settings.s3_region, "config": cfg}
        if settings.s3_endpoint:
            kwargs["endpoint_url"] = settings.s3_endpoint
        self.client = boto3.client("s3", **kwargs)
        self.bucket = settings.s3_bucket

    def exists(self, key: str) -> bool:
        try:
            self.client.head_object(Bucket=self.bucket, Key=key)
            return True
        except ClientError as exc:
            if exc.response.get("Error", {}).get("Code") in _NOT_FOUND:
                return False
            raise

    def put(self, key: str, body, content_type: str = "application/json", metadata=None) -> int:
        data = body.encode("utf-8") if isinstance(body, str) else body
        extra = {}
        if metadata:
            # S3 user metadata (x-amz-meta-*): keys/values must be strings.
            extra["Metadata"] = {k: str(v) for k, v in metadata.items()}
        self.client.put_object(
            Bucket=self.bucket, Key=key, Body=data, ContentType=content_type, **extra
        )
        return len(data)

    def put_json(self, key: str, obj: dict, metadata=None) -> int:
        return self.put(key, json.dumps(obj, ensure_ascii=False), "application/json", metadata)

    def iter_keys(self, prefix: str):
        """prefix 아래 모든 오브젝트 키 (pagination 처리)."""
        paginator = self.client.get_paginator("list_objects_v2")
        for page in paginator.paginate(Bucket=self.bucket, Prefix=prefix):
            for item in page.get("Contents", []):
                yield item["Key"]

    def get_json(self, key: str) -> dict:
        body = self.client.get_object(Bucket=self.bucket, Key=key)["Body"].read()
        return json.loads(body)

    def dead_letter(self, job, date, item_id, url, error, attempts, ts) -> int:
        return self.put_json(
            keys.dead_letter_key(job, date, item_id),
            {"url": url, "error": str(error), "attempts": attempts, "ts": ts},
        )
