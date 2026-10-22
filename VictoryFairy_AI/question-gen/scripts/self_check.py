"""생성 서브에이전트용 자기 점검 헬퍼 (2026-10-03 신설).

병렬 위임된 유닛 서브에이전트(경기·팀·공통 — ROUTINE.md §3~5)가 자기 산출물을
핸드백 직전에 스스로 점검하는 용도다. 2026-09-30 실행에서 그날 세션이 즉석으로
만들어 쓴 헬퍼를 리포에 반영해 트랙킹·재사용 가능하게 했다 — 매 실행마다
새로 발명하면 실행마다 품질이 들쑥날쑥해진다.

`validate_candidates.py`의 모든 결정적 검사(스키마·카탈로그·포인트·안전·
subject)를 그대로 돌리고, 추가로 evidence.quote가 실제 소스 파일에 존재하는지
(`runner.finalize.check_evidence`와 동일 로직)도 KNOWLEDGE 문항마다 확인해
[EVIDENCE-FAIL]로 표시한다.

placeholder quizId(예: `team-HH-001`처럼 최종 `QZ-{date}-NNN` 형식이 아닌 값)를
그대로 써도 동작한다 — 결정적 검사에는 quizId *값*이 쓰이지 않는다(최종
`quizId` 부여는 ROUTINE.md §6에서 `runner.finalize.assign_and_write`가 한다).

사용법:
    python3 question-gen/scripts/self_check.py <raw-candidates-dir> \
        [--work .work] [--repo-root .] \
        [--catalog PATH] [--banned PATH] [--scoring PATH]
"""
import argparse
import json
import sys
from pathlib import Path

_SCRIPTS_DIR = Path(__file__).resolve().parent
_REPO_ROOT = _SCRIPTS_DIR.parents[1]
sys.path.insert(0, str(_SCRIPTS_DIR))
sys.path.insert(0, str(_REPO_ROOT / "runner"))

import validate_candidates as vc  # noqa: E402
from runner.finalize import check_evidence  # noqa: E402


def _default_config_path(name: str) -> str:
    return str(_REPO_ROOT / "question-gen" / "config" / name)


def _build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="raw-candidates 디렉토리를 결정적 검사 + evidence 원문 대조로 점검한다"
                    "(서브에이전트 핸드백 직전 자기 점검용).")
    p.add_argument("dir", help="raw-candidates *.json이 있는 디렉토리")
    p.add_argument("--work", default=".work",
                   help="evidence 소스 상대경로의 기준 work 루트(기본: .work)")
    p.add_argument("--repo-root", default=".", help="리포 루트(기본: 현재 디렉토리)")
    p.add_argument("--catalog", default=None, help="질문 템플릿 카탈로그 YAML 경로")
    p.add_argument("--banned", default=None, help="banned-topics.txt 경로")
    p.add_argument("--scoring", default=None, help="점수 기준표 YAML 경로")
    return p


def main(argv=None) -> None:
    """위반이 하나라도 있으면 `sys.exit(1)`, 없으면 `sys.exit(0)` —
    `validate_candidates.py`와 같은 관례(서브에이전트가 이 exit code로 핸드백
    여부를 결정한다)."""
    args = _build_arg_parser().parse_args(argv)

    catalog_path = args.catalog or _default_config_path("question-templates.yaml")
    banned_path = args.banned or _default_config_path("banned-topics.txt")
    catalog = vc.load_catalog(catalog_path)
    banned = vc.load_banned(banned_path)
    if args.scoring:
        vc.POINTS.clear()
        vc.POINTS.update(vc.load_scoring(args.scoring))

    work = Path(args.work)
    repo_root = Path(args.repo_root)

    files = sorted(Path(args.dir).glob("*.json"))
    fail_count = 0
    for f in files:
        try:
            data = json.loads(f.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as e:
            print(f"[FAIL] {f.name}\n  - JSON 파싱 실패: {e}")
            fail_count += 1
            continue

        violations = vc.validate_candidate(data, catalog, banned)
        if not check_evidence(work, repo_root, data):
            violations.append("[EVIDENCE-FAIL] evidence.quote가 소스 파일에서 확인되지 않음")

        if violations:
            fail_count += 1
            print(f"[FAIL] {f.name}")
            for v in violations:
                print(f"  - {v}")
        else:
            print(f"[OK] {f.name}")

    print(f"\n총 {len(files)}개 중 통과 {len(files) - fail_count}개, 실패 {fail_count}개")
    sys.exit(1 if fail_count else 0)


if __name__ == "__main__":
    main()
