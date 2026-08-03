import pytest
from runner.bedrock_client import BedrockClient, BedrockJsonError


def _resp(text):
    return {"content": [{"type": "text", "text": text}]}


def test_invoke_json_parses_fenced_json():
    client = BedrockClient("ap-northeast-2",
                           transport=lambda m, b: _resp('```json\n[{"a": 1}]\n```'))
    assert client.invoke_json("model-x", "sys", "user") == [{"a": 1}]


def test_invoke_json_retries_once_then_raises():
    calls = []

    def transport(m, b):
        calls.append(b)
        return _resp("이건 JSON이 아님")

    client = BedrockClient("ap-northeast-2", transport=transport)
    with pytest.raises(BedrockJsonError):
        client.invoke_json("model-x", "sys", "user")
    assert len(calls) == 2                              # 원호출 + 재시도 1
    assert "JSON" in calls[1]["messages"][-1]["content"]  # 재시도에 교정 지시 포함
