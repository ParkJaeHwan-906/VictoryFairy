import hashlib


def mask_author(author: str, salt: str) -> str:
    """Salted SHA-256 of the author handle, truncated to 12 hex chars.

    PII hygiene, not censorship: we never store the raw handle. Deterministic
    per (author, salt) so the same commenter maps to the same token within a run.
    """
    if not author:
        return ""
    digest = hashlib.sha256(f"{salt}:{author}".encode("utf-8")).hexdigest()
    return digest[:12]
