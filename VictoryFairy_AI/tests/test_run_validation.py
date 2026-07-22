"""pipeline.run_validation.process_post 단위 테스트 (S3 불필요, 순수 dict in/out).

pytest 없이 stdlib 로만 실행:  `python3 tests/test_run_validation.py`
(관행상 시스템 python3(3.9)로 실행되던 tests/test_validation.py 와 달리, 이 파일은
`pipeline.run_validation` 을 import 하며 그 경로가 pydantic·pydantic-settings 를
필요로 한다. 로컬 .venv 에는 두 패키지 모두 설치돼 있어 `.venv/bin/python3` 로 실행하면
정상 동작한다. 시스템 python3 에 pydantic 이 없으면 이 파일만 별도로 .venv 로 돌릴 것.)

검열 판정 경계선(무엇이 욕설인지)은 다루지 않는다 — 그건 validation_service/
docs/requirements/validation 소관이다. 여기서는 "명백히 걸리는 문장"과 "명백히
통과하는 문장"만 써서 라우팅/정화/구조(PIPE-S3IO-7~20c, 13)만 검증한다.

요구사항 ID 대응 (docs/requirements/pipeline/s3-io.md):
  PIPE-S3IO-9   test_all_units_pass_returns_original_success_no_failed
  PIPE-S3IO-10  test_partial_comment_discard_purifies_success_and_failed
  PIPE-S3IO-13  test_failed_reason_structure_and_text_field
  PIPE-S3IO-17  test_body_discarded_with_passing_comment_keeps_purified_success
  PIPE-S3IO-18  test_body_pass_all_comments_discarded_empty_top_comments
  PIPE-S3IO-19  test_no_passing_unit_returns_no_success
  PIPE-S3IO-20  test_all_units_pass_returns_original_success_no_failed (failed==[])
  PIPE-S3IO-20b test_empty_body_treated_as_discarded
  PIPE-S3IO-20c test_missing_or_empty_top_comments_no_crash
  (PIPE-S3IO-7/8 은 위 케이스들이 간접적으로 검증한다: 각 단위가 독립 판정되고
   validation_service 를 그대로 거치는지는 BAD/GOOD 문장의 판정 결과로 확인된다.)
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from pipeline.run_validation import process_post  # noqa: E402

# 명백히 걸리는 문장 / 명백히 통과하는 문장 (판정 경계선은 다루지 않음).
BAD = "씨발 진짜"
GOOD = "오늘 경기 재밌었다"
GOOD_2 = "다음에도 응원하러 가야지"


def _post(body, comments=None, **extra):
    post = {
        "schemaVersion": 2,
        "source": "DCINSIDE",
        "postExternalId": "11229559",
        "sourceUrl": "https://example.com/1",
        "title": "제목",
        "body": body,
        "engagement": {"likeCount": 1},
        "topComments": comments if comments is not None else [],
        "team": "DOOSAN",
        "crawledAt": "2026-07-21T15:09:21+00:00",
        "crawlerVersion": "community-v3",
    }
    post.update(extra)
    return post


def _comment(author, body):
    return {"author": author, "body": body, "likeCount": 19}


def test_all_units_pass_returns_original_success_no_failed():
    # PIPE-S3IO-9/20: 전건 통과 -> success == 원본(필드 무변형), failed 없음.
    comments = [_comment("a", GOOD), _comment("b", GOOD_2)]
    post = _post(GOOD, comments)

    success, failed = process_post(post)

    assert success == post, f"success 가 원본과 다름: {success}"
    assert failed == [], f"전건 통과인데 failed 발생: {failed}"


def test_partial_comment_discard_purifies_success_and_failed():
    # PIPE-S3IO-10: 본문 통과 + 댓글 일부 폐기 -> 정화 success(통과 댓글만) +
    # failed(폐기 댓글) 가 동시에 존재.
    comments = [_comment("a", GOOD), _comment("b", BAD), _comment("c", GOOD_2)]
    post = _post(GOOD, comments)

    success, failed = process_post(post)

    assert success is not None
    assert success["body"] == GOOD
    assert [c["author"] for c in success["topComments"]] == ["a", "c"], success["topComments"]
    assert len(failed) == 1
    assert failed[0]["unit"] == "comment"
    assert failed[0]["commentIndex"] == 1
    assert failed[0]["author"] == "b"


def test_body_discarded_with_passing_comment_keeps_purified_success():
    # PIPE-S3IO-17: 본문 폐기 + 통과 댓글 있음 -> success body:"" + 통과 댓글 유지,
    # failed 에 body 사유.
    comments = [_comment("a", GOOD), _comment("b", GOOD_2)]
    post = _post(BAD, comments)

    success, failed = process_post(post)

    assert success is not None
    assert success["body"] == ""
    assert [c["author"] for c in success["topComments"]] == ["a", "b"]
    # 원본 필드(제목 등)는 보존.
    assert success["title"] == post["title"]
    assert success["postExternalId"] == post["postExternalId"]

    body_reasons = [r for r in failed if r["unit"] == "body"]
    assert len(body_reasons) == 1


def test_body_pass_all_comments_discarded_empty_top_comments():
    # PIPE-S3IO-18: 본문 통과 + 댓글 전건 폐기 -> success topComments:[].
    comments = [_comment("a", BAD), _comment("b", BAD)]
    post = _post(GOOD, comments)

    success, failed = process_post(post)

    assert success is not None
    assert success["body"] == GOOD
    assert success["topComments"] == []
    assert len(failed) == 2
    assert all(r["unit"] == "comment" for r in failed)


def test_no_passing_unit_returns_no_success():
    # PIPE-S3IO-19: 통과 단위 0 -> success None, failed만.
    comments = [_comment("a", BAD), _comment("b", BAD)]
    post = _post(BAD, comments)

    success, failed = process_post(post)

    assert success is None
    assert len(failed) == 3  # body 1 + comment 2
    units = [r["unit"] for r in failed]
    assert units.count("body") == 1
    assert units.count("comment") == 2


def test_empty_body_treated_as_discarded():
    # PIPE-S3IO-20b: 빈 본문(body:"") -> 폐기로 간주, failed 에 "빈 본문" 사유.
    post = _post("", comments=[])

    success, failed = process_post(post)

    assert success is None  # 통과 단위 전무(댓글도 없음) -> PIPE-S3IO-19 규칙과 결합.
    assert len(failed) == 1
    assert failed[0]["unit"] == "body"
    assert failed[0]["message"] == "빈 본문"


def test_empty_body_with_passing_comment_still_yields_purified_success():
    # PIPE-S3IO-20b + 17 결합: 빈 본문이라도 통과 댓글이 있으면 success 는 생성된다.
    post = _post("", comments=[_comment("a", GOOD)])

    success, failed = process_post(post)

    assert success is not None
    assert success["body"] == ""
    assert [c["author"] for c in success["topComments"]] == ["a"]
    assert failed[0]["unit"] == "body"
    assert failed[0]["message"] == "빈 본문"


def test_whitespace_only_body_treated_as_discarded():
    # PIPE-S3IO-20b 의 "공백" 케이스 — strip 후 빈 문자열도 폐기.
    post = _post("   ", comments=[])

    success, failed = process_post(post)

    assert success is None
    assert failed[0]["unit"] == "body"
    assert failed[0]["message"] == "빈 본문"


def test_missing_or_empty_top_comments_no_crash():
    # PIPE-S3IO-20c: 댓글 없음(빈 배열) -> 본문만 판정, 크래시 없음.
    post = _post(GOOD, comments=[])
    success, failed = process_post(post)
    assert success is not None
    assert success["topComments"] == []
    assert failed == []

    # topComments 키 자체가 아예 없는 경우도 크래시 없이 처리돼야 한다.
    post_missing = _post(GOOD, comments=[])
    del post_missing["topComments"]
    success2, failed2 = process_post(post_missing)
    assert success2 is not None
    assert success2["topComments"] == []
    assert failed2 == []


def test_failed_reason_structure_and_text_field():
    # PIPE-S3IO-13: failed 사유 구조 {unit, commentIndex, author, text, message}.
    # body 사유의 text == 원본 본문, comment 사유의 text == 원본 댓글 body.
    comments = [_comment("author1", BAD)]
    post = _post(BAD, comments)

    success, failed = process_post(post)

    assert success is None
    assert len(failed) == 2

    body_reason = next(r for r in failed if r["unit"] == "body")
    assert set(body_reason.keys()) == {"unit", "commentIndex", "author", "text", "message"}
    assert body_reason["commentIndex"] is None
    assert body_reason["author"] is None
    assert body_reason["text"] == BAD  # 걸린 원본 본문 텍스트 그대로.
    assert isinstance(body_reason["message"], str) and body_reason["message"]

    comment_reason = next(r for r in failed if r["unit"] == "comment")
    assert set(comment_reason.keys()) == {"unit", "commentIndex", "author", "text", "message"}
    assert comment_reason["commentIndex"] == 0
    assert comment_reason["author"] == "author1"
    assert comment_reason["text"] == BAD  # 걸린 원본 댓글 텍스트 그대로.


def test_comments_independent_of_each_other_and_body():
    # PIPE-S3IO-7: body + 댓글 각각 독립 판정 -> 서로 결과에 영향 없음.
    comments = [_comment("a", BAD), _comment("b", GOOD)]
    post = _post(GOOD, comments)  # body 통과, 댓글1 폐기, 댓글2 통과.

    success, failed = process_post(post)

    assert success["body"] == GOOD
    assert [c["author"] for c in success["topComments"]] == ["b"]
    assert len(failed) == 1 and failed[0]["author"] == "a"


if __name__ == "__main__":
    tests = [fn for name, fn in sorted(globals().items())
             if name.startswith("test_") and callable(fn)]
    failed_count = 0
    for fn in tests:
        try:
            fn()
            print(f"PASS  {fn.__name__}")
        except AssertionError as exc:
            failed_count += 1
            print(f"FAIL  {fn.__name__}: {exc}")
    print(f"\n{len(tests) - failed_count}/{len(tests)} passed")
    sys.exit(1 if failed_count else 0)
