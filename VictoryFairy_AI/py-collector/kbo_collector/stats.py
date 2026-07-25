"""Success/failure report for a crawl run.

Reads the local journal (per-item ok/skip/dead-letter/list-fail lines) and the
S3 dead-letter objects (whose error strings carry the HTTP status of the last
failed attempt) and prints a success rate plus an HTTP-code breakdown.

    python -m kbo_collector.stats community --date 2026-07-11
"""
import argparse
import json
import re
from collections import Counter
from pathlib import Path

import boto3
from botocore.config import Config

from .config import get_settings

# httpx renders 4xx/5xx as "Client error '403 Forbidden'" / "Server error '500 ...'".
_CODE_RE = re.compile(r"(?:Client|Server) error '(\d{3})")
_TRANSPORT = ("Timeout", "ConnectError", "ConnectTimeout", "ReadTimeout",
              "TransportError", "RemoteProtocolError")


def http_code(error: str) -> str:
    """Classify a dead-letter error string into an HTTP code or a coarse bucket."""
    m = _CODE_RE.search(error or "")
    if m:
        return m.group(1)
    if any(t in (error or "") for t in _TRANSPORT):
        return "transport"
    return "other"  # non-HTTP failure (e.g. a parse error on live DOM)


def _s3_client(settings):
    cfg = Config(s3={"addressing_style": "path" if settings.s3_path_style else "auto"})
    kwargs = {"region_name": settings.s3_region, "config": cfg}
    if settings.s3_endpoint:
        kwargs["endpoint_url"] = settings.s3_endpoint
    return boto3.client("s3", **kwargs)


def read_journal(path: Path):
    """Return (status counts, HTTP-code histogram of list-page failures)."""
    counts: Counter = Counter()
    list_codes: Counter = Counter()
    if not path.exists():
        return counts, list_codes
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        rec = json.loads(line)
        counts[rec.get("status", "?")] += 1
        if rec.get("status") == "list-fail":
            list_codes[http_code(rec.get("error", ""))] += 1
    return counts, list_codes


def read_journal_counts(path: Path) -> Counter:
    return read_journal(path)[0]


def read_dead_letter_codes(settings, job: str, date: str) -> Counter:
    """HTTP-code histogram over the run's dead-letter objects in S3."""
    client = _s3_client(settings)
    prefix = f"dead-letter/{job}/{date}/"
    codes: Counter = Counter()
    token = None
    while True:
        kw = {"Bucket": settings.s3_bucket, "Prefix": prefix}
        if token:
            kw["ContinuationToken"] = token
        resp = client.list_objects_v2(**kw)
        for obj in resp.get("Contents", []):
            body = client.get_object(Bucket=settings.s3_bucket, Key=obj["Key"])["Body"].read()
            codes[http_code(json.loads(body).get("error", ""))] += 1
        if not resp.get("IsTruncated"):
            break
        token = resp.get("NextContinuationToken")
    return codes


def summarize(counts: Counter, codes: Counter, list_codes: Counter | None = None) -> str:
    ok = counts.get("ok", 0)
    skip = counts.get("skip", 0)
    dead = counts.get("dead-letter", 0)
    list_fail = counts.get("list-fail", 0)
    pages = counts.get("page", 0)
    attempted = ok + dead  # detail fetches that ran (skips were checkpointed, not fetched)
    rate = (ok / attempted * 100) if attempted else 0.0

    lines = [
        "=== crawl success/failure ===",
        f"detail attempted : {attempted}",
        f"  landed (2xx ok): {ok}",
        f"  failed         : {dead}",
        f"  success rate   : {rate:.1f}%",
        f"skipped (already in S3): {skip}",
        f"list-page failures     : {list_fail}",
        f"list pages walked      : {pages}",
    ]
    if codes:
        lines.append("--- failures by HTTP code ---")
        for code, n in sorted(codes.items(), key=lambda kv: (-kv[1], kv[0])):
            note = ""
            if code in ("403", "429"):
                note = "  <- likely rate-limit / block"
            elif code == "transport":
                note = "  <- timeout / connection"
            lines.append(f"  {code}: {n}{note}")
    elif dead:
        lines.append("(dead-letter objects not yet readable from S3)")
    if list_codes:
        lines.append("--- list-page failures by HTTP code ---")
        for code, n in sorted(list_codes.items(), key=lambda kv: (-kv[1], kv[0])):
            note = "  <- rate-limit / block" if code in ("403", "429", "430") else ""
            lines.append(f"  {code}: {n}{note}")
    return "\n".join(lines)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(prog="kbo_collector.stats")
    parser.add_argument("job", default="community", nargs="?")
    parser.add_argument("--date", required=True, help="YYYY-MM-DD")
    args = parser.parse_args(argv)

    settings = get_settings()
    counts, list_codes = read_journal(Path(settings.journal_dir) / args.job / f"{args.date}.jsonl")
    codes = read_dead_letter_codes(settings, args.job, args.date) if counts.get("dead-letter") else Counter()
    print(summarize(counts, codes, list_codes))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
