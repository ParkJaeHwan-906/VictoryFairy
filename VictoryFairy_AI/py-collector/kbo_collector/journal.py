import json
import logging
import sys
from pathlib import Path

_LOG_FIELDS = ("job", "run_id", "source", "item_id", "s3_key", "bytes", "status")


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        base = {
            "ts": self.formatTime(record),
            "level": record.levelname,
            "msg": record.getMessage(),
        }
        for field in _LOG_FIELDS:
            value = getattr(record, field, None)
            if value is not None:
                base[field] = value
        return json.dumps(base, ensure_ascii=False)


def setup_logging(level: str = "INFO") -> logging.Logger:
    logger = logging.getLogger("kbo_collector")
    logger.setLevel(level)
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter())
    logger.handlers = [handler]
    logger.propagate = False
    return logger


class Journal:
    """Append-only JSONL journal, one line per landed/skipped/failed item.

    Line-buffered flush per record so a crash leaves only a valid prefix.
    """

    def __init__(self, job: str, date: str, run_id: str, journal_dir: str):
        self.job = job
        self.run_id = run_id
        self.path = Path(journal_dir) / job / f"{date}.jsonl"
        self.path.parent.mkdir(parents=True, exist_ok=True)

    def record(self, **fields) -> None:
        line = {"job": self.job, "run_id": self.run_id, **fields}
        with self.path.open("a", encoding="utf-8") as f:
            f.write(json.dumps(line, ensure_ascii=False) + "\n")
            f.flush()
