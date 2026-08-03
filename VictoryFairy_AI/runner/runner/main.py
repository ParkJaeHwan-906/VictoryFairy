"""파이프라인 오케스트레이션 — run() 조립 + CLI entrypoint.

순서(스펙 고정): available_needs → enumerate_entities(eligible 템플릿) →
select_combos → bind → run_generate → run_judge → check_evidence(전 후보) →
select_final → assign_and_write. 실 업로드(S3)는 이 모듈의 책무가 아니다
— entrypoint(호출자)가 uploadedDir을 보고 결정한다.
"""
import argparse
import json
import sys
from pathlib import Path

from .binding import (available_needs, bind, enumerate_entities,
                      recent_summaries, recent_template_counts)
from .catalog import load_catalog, select_combos
from .config import RunnerConfig
from .finalize import assign_and_write, check_evidence, select_final
from .generate import run_generate
from .judge import run_judge

CATALOG_REL = "question-gen/config/question-templates.yaml"


def _entity_by_template(combos) -> dict:
    """templateId → combos에서 첫 매칭 엔티티. run_generate의 RAW 재번호는
    combos 순번과 1:1이 아니므로, 후보 매핑은 반드시 templateId로 한다."""
    out = {}
    for template, entity in combos:
        out.setdefault(template["id"], entity)
    return out


def run(work: Path, repo_root: Path, today: str, client, model_c1: str, model_c2: str) -> dict:
    catalog = load_catalog(repo_root / CATALOG_REL)
    available = available_needs(work, today)

    eligible = [t for t in catalog
               if t["enabled"] and set(t.get("needs") or []) <= available]
    entities_by_template = {
        t["id"]: enumerate_entities(work, t, today, repo_root=repo_root)
        for t in eligible
    }
    combos = select_combos(catalog, available, entities_by_template,
                           recent_template_counts(work))
    bindings = [bind(work, t, entity, today, repo_root=repo_root) for t, entity in combos]

    out_dir = work / "candidates" / today
    discarded = []

    candidates = run_generate(client, model_c1, repo_root, combos, bindings, today)
    if not candidates:
        return {"uploadedDir": str(out_dir), "written": [],
                "discarded": discarded, "combos": len(combos)}

    verdicts = run_judge(client, model_c2, repo_root, candidates, recent_summaries(work))

    entity_by_template = _entity_by_template(combos)
    entity_of = {}
    verified = []
    for cand in candidates:
        entity = entity_by_template.get(cand.get("templateId"))
        # 오케스트레이션 순서 계약: check_evidence는 "전 후보"에 대해 실행한다 —
        # 엔티티 매칭 실패라고 evidence 검사를 건너뛰지 않는다(두 실패가 겹치면
        # 사유를 합쳐서 남긴다).
        evidence_ok = check_evidence(work, repo_root, cand)
        cand_reasons = []
        if entity is None:
            cand_reasons.append(f"templateId({cand.get('templateId')})가 combos에 없음 — "
                                "엔티티 매칭 실패")
        if not evidence_ok:
            cand_reasons.append("evidence 원문 대조 실패")
        if cand_reasons:
            discarded.append(f"{cand['quizId']}: " + "; ".join(cand_reasons))
            continue
        entity_of[cand["quizId"]] = entity
        verified.append(cand)

    final, reasons = select_final(verified, verdicts, entity_of)
    discarded.extend(reasons)

    paths = assign_and_write(final, entity_of, work, today, discarded)

    return {"uploadedDir": str(out_dir), "written": [p.stem for p in paths],
            "discarded": discarded, "combos": len(combos)}


def _parse_args(argv=None):
    parser = argparse.ArgumentParser(prog="python -m runner.main")
    parser.add_argument("--work", required=True)
    parser.add_argument("--repo-root", required=True)
    parser.add_argument("--date", required=True)
    return parser.parse_args(argv)


def main(argv=None) -> int:
    args = _parse_args(argv)
    try:
        cfg = RunnerConfig.from_env()
        from .bedrock_client import BedrockClient
        client = BedrockClient(cfg.region)
        summary = run(Path(args.work), Path(args.repo_root), args.date,
                      client, cfg.model_c1, cfg.model_c2)
    except Exception as exc:
        print(json.dumps({"error": str(exc)}, ensure_ascii=False), file=sys.stderr)
        return 1
    print(json.dumps(summary, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
