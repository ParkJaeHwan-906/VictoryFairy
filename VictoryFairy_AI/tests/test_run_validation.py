"""pipeline.run_validation.process_post 단위 테스트 (S3 불필요, 순수 dict in/out).

pytest 없이 stdlib 로만 실행:  `python3 tests/test_run_validation.py`
(관행상 시스템 python3(3.9)로 실행되던 tests/test_validation.py 와 달리, 이 파일은
`pipeline.run_validation` 을 import 하며 그 경로가 pydantic·pydantic-settings 를
필요로 한다. 로컬 .venv 에는 두 패키지 모두 설치돼 있어 `.venv/bin/python3` 로 실행하면
정상 동작한다. 시스템 python3 에 pydantic 이 없으면 이 파일만 별도로 .venv 로 돌릴 것.)

검열 판정 경계선(무엇이 욕설인지)은 다루지 않는다 — 그건 validation_service/
docs/requirements/validation 소관이다. 여기서는 "명백히 걸리는 문장"과 "명백히
통과하는 문장"만 써서 라우팅/정화/구조(PIPE-S3IO-7~20c, 13, 32~35)만 검증한다.

⚠️ v2 개정 반영(docs/requirements/pipeline/s3-io.md, 2026-07-25 승인):
  - PIPE-S3IO-17 은 폐기됨 — `body:""` success 산출물은 더 이상 없다.
  - PIPE-S3IO-19 개정 — 본문 판정 단위(body, 비어 있으면 title)가 폐기되면 통과 댓글이
    있어도 게시글 전체가 fail. 이전 v1 은 "통과 단위가 하나라도 있으면 success" 였다.
  - PIPE-S3IO-32~35 신규 — body 가 비면 title 을 본문 자리 판정 단위로 대신 쓴다.
  이 개정으로 v1 을 전제로 쓰였던 옛 테스트 4건이 깨졌었고, 이 파일에서 v2 계약대로
  다시 썼다(아래 매핑의 "구 v1 테스트, v2로 재작성" 표기 참고).

요구사항 ID 대응 (docs/requirements/pipeline/s3-io.md):
  PIPE-S3IO-9   test_all_units_pass_returns_original_success_no_failed
  PIPE-S3IO-10  test_partial_comment_discard_purifies_success_and_failed
  PIPE-S3IO-13  test_failed_reason_structure_and_text_field
  PIPE-S3IO-17  (폐기됨) — 대체 테스트 없음. 아래 -19/-19b 가 대신한다.
  PIPE-S3IO-18  test_body_pass_all_comments_discarded_empty_top_comments
  PIPE-S3IO-19  test_no_passing_unit_returns_no_success,
                test_body_discarded_yields_full_post_fail_even_with_passing_comments,
                test_empty_body_with_failing_title_yields_full_post_fail_with_title_unit
                (구 v1 테스트 test_body_discarded_with_passing_comment_keeps_purified_success
                 를 v2 계약대로 재작성 — v1 은 "통과 댓글이 있으면 success 유지" 를 기대했으나
                 v2 는 본문 폐기 시 통과 댓글이 있어도 success 를 만들지 않는다)
  PIPE-S3IO-19b test_body_discard_also_reports_failing_comment_reasons
  PIPE-S3IO-20  test_all_units_pass_returns_original_success_no_failed (failed==[])
  PIPE-S3IO-20b test_whitespace_only_body_is_blank_and_uses_title_as_body_slot
                (구 v1 테스트 test_empty_body_treated_as_discarded /
                 test_whitespace_only_body_treated_as_discarded 를 v2 계약대로 재작성 —
                 v1 은 빈 본문을 무조건 폐기로 봤으나 v2 는 title 대체 판정을 거친다)
  PIPE-S3IO-20c test_missing_or_empty_top_comments_no_crash
  PIPE-S3IO-32  test_empty_body_with_passing_title_uses_title_as_body_slot_and_succeeds
                (구 v1 테스트 test_empty_body_with_passing_comment_still_yields_purified_success
                 를 대체 — v1 은 title 대체 판정 자체가 없어 IndexError 로 크래시했다)
  PIPE-S3IO-33  test_empty_body_and_empty_title_fails_with_combined_message
  PIPE-S3IO-34  test_empty_body_with_failing_title_yields_full_post_fail_with_title_unit
  PIPE-S3IO-35  test_body_present_title_not_validated_even_if_offensive
  (PIPE-S3IO-7/8 은 위 케이스들이 간접적으로 검증한다: 각 단위가 독립 판정되고
   validation_service 를 그대로 거치는지는 BAD/GOOD 문장의 판정 결과로 확인된다.)

⚠️ 이 파일의 러너는 AssertionError 외의 예외도 잡아 해당 테스트만 FAIL 로 기록하고
나머지 테스트를 계속 실행한다(test_s3_io.py 의 러너 관례를 따름). 이전에는
AssertionError 만 잡아 그 외 예외(예: IndexError)가 나면 프로세스 전체가 죽어
뒤 테스트들이 실행조차 안 되는 상태였다 — 회귀를 가리는 가장 위험한 형태였다.
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


def test_body_discarded_yields_full_post_fail_even_with_passing_comments():
    # PIPE-S3IO-19(v2 개정) / 17(폐기됨): 본문 폐기 + 통과 댓글이 있어도 게시글
    # 전체가 fail -> success 미생성. v1 은 이 경우 success(body:"")를 만들었으나
    # v2 는 통과 댓글 존재 여부와 무관하게 success 를 만들지 않는다.
    comments = [_comment("a", GOOD), _comment("b", GOOD_2)]
    post = _post(BAD, comments)

    success, failed = process_post(post)

    assert success is None, f"v2 는 본문 폐기 시 success 를 만들지 않아야 하는데 생성됨: {success}"

    body_reasons = [r for r in failed if r["unit"] == "body"]
    assert len(body_reasons) == 1
    assert body_reasons[0]["text"] == BAD


def test_body_discard_also_reports_failing_comment_reasons():
    # PIPE-S3IO-19b: 본문 폐기가 확정돼도 댓글은 마저 판정해 그 사유를 failed 에
    # 함께 담는다(구현이 택한 쪽 — "댓글 판정 생략 허용"이 아니라 "댓글도 판정").
    comments = [_comment("a", BAD), _comment("b", GOOD)]
    post = _post(BAD, comments)

    success, failed = process_post(post)

    assert success is None
    units = [r["unit"] for r in failed]
    assert units.count("body") == 1
    assert units.count("comment") == 1  # 통과한 댓글("b")은 failed 에 안 남는다.
    comment_reason = next(r for r in failed if r["unit"] == "comment")
    assert comment_reason["author"] == "a"


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


def test_empty_body_with_passing_title_uses_title_as_body_slot_and_succeeds():
    # PIPE-S3IO-32: body 가 비면 title 을 본문 자리 판정 단위로 쓴다. title(기본값
    # "제목")이 통과하면 success 가 생성된다 — v1 은 이 케이스 자체가 없어
    # "빈 본문 = 무조건 폐기"였고(구 test_empty_body_treated_as_discarded 가 그
    # 가정으로 success is None 을 기대해 v2 에서 깨졌다), v2 는 title 에 실질
    # 내용이 있으면 게시글을 살린다(13.7% 실측 근거).
    post = _post("", comments=[])

    success, failed = process_post(post)

    assert success is not None, "title 대체 판정이 통과했으면 success 가 생성돼야 함"
    # success 의 body 필드는 title 로 바꿔치기되지 않고 원본(빈 문자열) 그대로 유지된다
    # (title 은 판정에만 쓰였을 뿐 정화 대상 필드가 아니라는 설계 — process_post 주석 참고).
    assert success["body"] == ""
    assert success["title"] == "제목"
    assert failed == []


def test_empty_body_with_passing_title_and_passing_comment_keeps_comment():
    # PIPE-S3IO-32 + 10 결합: 빈 본문이라도 title 판정이 통과하면 통과 댓글도
    # 정화 success 에 그대로 남는다(구 test_empty_body_with_passing_comment_
    # still_yields_purified_success 가 기대했던 "댓글 유지"만 유효한 부분을 승계 —
    # 단, v1 은 body 자체가 폐기 처리돼 failed[0] 를 기대해 IndexError 로
    # 크래시했었고, v2 는 title 이 통과하면 애초에 failed 가 비어 있다).
    post = _post("", comments=[_comment("a", GOOD)])

    success, failed = process_post(post)

    assert success is not None
    assert success["body"] == ""
    assert [c["author"] for c in success["topComments"]] == ["a"]
    assert failed == []


def test_empty_body_with_failing_title_yields_full_post_fail_with_title_unit():
    # PIPE-S3IO-19/34: 빈 body + 욕설 title -> title 이 본문 자리 판정 단위가 돼
    # 걸리므로 게시글 전체 fail(통과 댓글이 있어도 마찬가지, PIPE-S3IO-19).
    # failed 의 unit 은 "body" 가 아니라 "title" 로 기록된다(PIPE-S3IO-34).
    post = _post("", comments=[_comment("a", GOOD)], title=BAD)

    success, failed = process_post(post)

    assert success is None
    title_reasons = [r for r in failed if r["unit"] == "title"]
    assert len(title_reasons) == 1, f"unit=='title' 인 사유가 없음: {failed}"
    assert title_reasons[0]["text"] == BAD
    assert title_reasons[0]["commentIndex"] is None
    assert title_reasons[0]["author"] is None


def test_empty_body_and_empty_title_fails_with_combined_message():
    # PIPE-S3IO-33: body·title 둘 다 비어 있으면 판정할 텍스트가 없어 fail 확정,
    # 사유는 "빈 본문·빈 제목".
    post = _post("", comments=[], title="")

    success, failed = process_post(post)

    assert success is None
    combined_reasons = [r for r in failed if r["message"] == "빈 본문·빈 제목"]
    assert len(combined_reasons) == 1, f"'빈 본문·빈 제목' 사유가 없음: {failed}"


def test_body_present_title_not_validated_even_if_offensive():
    # PIPE-S3IO-35: body 가 있으면 title 은 판정 대상이 아니다 — title 이 욕설이라도
    # 게시글은 통과한다(v2 에서도 여전히 남는 공백, 알려진 한계로 문서화됨).
    post = _post(GOOD, comments=[], title=BAD)

    success, failed = process_post(post)

    assert success is not None, "body 가 통과했으면 title 은 검사되지 않아 게시글이 통과해야 함"
    assert success["title"] == BAD  # title 은 원본 그대로 보존(정화 대상 아님).
    assert failed == []


def test_whitespace_only_body_is_blank_and_uses_title_as_body_slot():
    # PIPE-S3IO-20b: 공백만 있는 body 도 "비어 있음"으로 간주해 title 대체 판정을
    # 태운다(리터럴 "" 뿐 아니라 strip 후 빈 문자열도 동일 경로). title(기본값
    # "제목")이 통과하므로 success 가 생성된다 — 구 v1 테스트는 공백 body 를
    # 무조건 폐기로 기대했으나(v1 규칙), v2 는 title 대체 판정 결과를 따른다.
    post = _post("   ", comments=[])

    success, failed = process_post(post)

    assert success is not None
    assert success["body"] == "   "  # 원본 보존(정화 대상 아님).
    assert failed == []


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
    passed = 0
    failed_count = 0
    for fn in tests:
        try:
            fn()
            passed += 1
            print(f"PASS  {fn.__name__}")
        except AssertionError as exc:
            failed_count += 1
            print(f"FAIL  {fn.__name__}: {exc}")
        except Exception as exc:  # noqa: BLE001 — 예상 못 한 예외도 이 테스트만 FAIL 처리하고 계속 진행.
            failed_count += 1
            print(f"FAIL  {fn.__name__}: {type(exc).__name__}: {exc}")
    print(f"\n{passed}/{len(tests)} passed")
    sys.exit(1 if failed_count else 0)
