import random

import httpx
from tenacity import (
    retry,
    retry_if_exception_type,
    stop_after_attempt,
    wait_exponential_jitter,
)

# FMKorea's anti-bot returns 430 to certain UA strings (observed: Mac Chrome/126,
# any Chrome/126.0.0.0 all-zeros build, mobile Safari) while accepting a current
# desktop Windows Chrome build. Keep this a realistic, recent Windows Chrome UA;
# if FMKorea starts 430-ing again, bump the version. (Naver/DCInside accept any.)
BROWSER_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.6778.86 Safari/537.36"
)

# Exceptions worth retrying: connection/timeout issues and 5xx / 429 responses.
_RETRYABLE = (httpx.TransportError, httpx.HTTPStatusError)
# Rate-limit statuses (429 standard, 430 = FMKorea's non-standard "slow down").
_RATE_LIMIT = {429, 430}


class FetchError(Exception):
    """Raised when a URL cannot be fetched after all retry attempts."""


def _make_wait(settings):
    """Fast exponential backoff for transient errors, but a long, jittered
    cooldown when the source rate-limits us (429/430) so we back right off."""
    fast = wait_exponential_jitter(
        initial=settings.retry_backoff_base, max=30, jitter=settings.retry_backoff_base)

    def _wait(retry_state) -> float:
        exc = retry_state.outcome.exception() if retry_state.outcome else None
        if isinstance(exc, httpx.HTTPStatusError) and exc.response.status_code in _RATE_LIMIT:
            return settings.rate_limit_cooldown_s + random.uniform(0, settings.retry_backoff_base)
        return fast(retry_state)

    return _wait


def _headers(referer: str | None, accept: str) -> dict[str, str]:
    h = {
        "User-Agent": BROWSER_UA,
        "Accept": accept,
        "Accept-Language": "ko-KR,ko;q=0.9",
    }
    if referer:
        h["Referer"] = referer
    return h


def build_client(settings) -> httpx.Client:
    return httpx.Client(timeout=settings.http_timeout_s, follow_redirects=True)


def fetch(client, url, *, settings, referer=None, accept="application/json") -> httpx.Response:
    @retry(
        reraise=True,
        stop=stop_after_attempt(settings.retry_attempts),
        wait=_make_wait(settings),
        retry=retry_if_exception_type(_RETRYABLE),
    )
    def _do() -> httpx.Response:
        resp = client.get(url, headers=_headers(referer, accept))
        resp.raise_for_status()  # raises HTTPStatusError on 4xx/5xx; both are retried (see _RETRYABLE)
        return resp

    try:
        return _do()
    except Exception as exc:  # includes final HTTPStatusError / TransportError
        raise FetchError(f"fetch failed after {settings.retry_attempts} attempts: {url}: {exc}") from exc
