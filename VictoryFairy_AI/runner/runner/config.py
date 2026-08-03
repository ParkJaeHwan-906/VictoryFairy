"""러너 설정 — 전부 환경변수에서. S3 자격증명은 다루지 않는다(IRSA/CLI 몫)."""
import os
from dataclasses import dataclass
from pathlib import Path

DEFAULT_MODEL_C1 = "global.anthropic.claude-sonnet-5"
DEFAULT_MODEL_C2 = "global.anthropic.claude-haiku-4-5-20251001-v1:0"


@dataclass(frozen=True)
class RunnerConfig:
    s3_bucket: str
    region: str
    model_c1: str
    model_c2: str
    repo_root: Path

    @classmethod
    def from_env(cls) -> "RunnerConfig":
        return cls(
            s3_bucket=os.environ["S3_BUCKET"],
            region=os.environ.get("AWS_DEFAULT_REGION", "ap-northeast-2"),
            model_c1=os.environ.get("MODEL_C1", DEFAULT_MODEL_C1),
            model_c2=os.environ.get("MODEL_C2", DEFAULT_MODEL_C2),
            repo_root=Path(os.environ.get("REPO_ROOT", "/app")),
        )
