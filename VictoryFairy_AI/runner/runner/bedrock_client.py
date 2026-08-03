"""Bedrock InvokeModel 래퍼 — JSON 강제·재시도. boto3는 여기서만 쓴다."""
import json
import time


class BedrockJsonError(RuntimeError):
    pass


def _extract_json(text: str):
    starts = [i for i in (text.find("["), text.find("{")) if i >= 0]
    if not starts:
        raise ValueError("no json start")
    s = min(starts)
    e = max(text.rfind("]"), text.rfind("}"))
    return json.loads(text[s:e + 1])


class BedrockClient:
    def __init__(self, region: str, transport=None):
        if transport is None:
            import boto3
            rt = boto3.client("bedrock-runtime", region_name=region)

            def transport(model_id, body):
                for attempt in range(3):
                    try:
                        resp = rt.invoke_model(modelId=model_id, body=json.dumps(body))
                        return json.loads(resp["body"].read())
                    except rt.exceptions.ThrottlingException:
                        if attempt == 2:
                            raise
                        time.sleep(2 ** (attempt + 1))
        self._transport = transport

    def invoke_json(self, model_id: str, system: str, user: str, max_tokens: int = 8000):
        messages = [{"role": "user", "content": user}]
        for attempt in range(2):
            body = {"anthropic_version": "bedrock-2023-05-31", "system": system,
                    "messages": messages, "max_tokens": max_tokens}
            resp = self._transport(model_id, body)
            text = "".join(c.get("text", "") for c in resp.get("content", []))
            try:
                return _extract_json(text)
            except (ValueError, json.JSONDecodeError):
                messages = messages + [
                    {"role": "assistant", "content": text},
                    {"role": "user", "content": "위 응답을 유효한 JSON만으로 다시 출력하라. 설명·코드펜스 금지."}]
        raise BedrockJsonError(f"{model_id}: JSON 파싱 2회 실패")
