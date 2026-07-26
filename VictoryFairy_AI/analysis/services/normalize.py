"""인명 정규화·집계 (엔티티 통합).

여러 문장에서 추출된 인명 표면형을 동일 인물로 묶어 집계한다. (설계 §116, §118)
    1. 별칭 사전(aliases.json) 매핑    — 규칙 기반, 가장 안전.
    2. 부분문자열 클러스터링           — '길동' ⊂ '홍길동' 처럼 포함 관계를 묶음.
    3. 집계                            — {정규화_인명, 표면형_리스트, 출현_문장_id, 빈도}.

주의(설계 §113): 동명이인은 문자열 집계로 구분 불가. 과도한 병합은 다른 인물을
합칠 위험이 있으므로, 한 글자 표면형은 클러스터링에서 제외한다.
"""

from analysis.core.resources import load_aliases

# 별칭 사전은 모듈 로드 시 1회 로드.
_ALIASES = load_aliases()

# 부분문자열 클러스터링 시 이 길이 미만 표면형은 병합 대상에서 제외(오병합 방지).
_MIN_CLUSTER_LEN = 2


def _canonical_map(surfaces: set[str]) -> dict[str, str]:
    """표면형 집합을 정규 인명으로 매핑한다.

    반환: {표면형: 정규화_인명}
    """
    # 1) 별칭 사전 우선 적용
    mapped = {s: _ALIASES.get(s, s) for s in surfaces}
    reps = sorted(set(mapped.values()))

    # 2) 부분문자열 포함 관계로 union-find 클러스터링 (대표 = 더 긴 표면형)
    parent = {r: r for r in reps}

    def find(x: str) -> str:
        root = x
        while parent[root] != root:
            root = parent[root]
        while parent[x] != root:  # 경로 압축
            parent[x], x = root, parent[x]
        return root

    def union(a: str, b: str) -> None:
        ra, rb = find(a), find(b)
        if ra == rb:
            return
        # 더 긴 쪽을 대표로(짧은 쪽을 붙인다). 길이 같으면 사전순 앞을 대표.
        if (len(ra), rb) < (len(rb), ra):
            ra, rb = rb, ra
        parent[rb] = ra

    for i, a in enumerate(reps):
        for b in reps[i + 1:]:
            shorter, longer = (a, b) if len(a) <= len(b) else (b, a)
            if len(shorter) >= _MIN_CLUSTER_LEN and shorter in longer:
                union(a, b)

    return {s: find(mapped[s]) for s in surfaces}


def aggregate_persons(records: list[dict]) -> list[dict]:
    """문장별 분석 결과에서 인명을 모아 인물별로 집계한다.

    - records: [{"문장_id": int, "names": [str, ...], ...}, ...]
    - return: [{"정규화_인명", "표면형_리스트", "출현_문장_id", "빈도"}, ...]
              빈도 내림차순 정렬.
    """
    occurrences: list[tuple[str, int]] = [
        (name, record["문장_id"])
        for record in records
        for name in record.get("names", [])
    ]

    surfaces = {name for name, _ in occurrences}
    canonical = _canonical_map(surfaces)

    groups: dict[str, dict] = {}
    for surface, sent_id in occurrences:
        canon = canonical[surface]
        group = groups.setdefault(
            canon,
            {"정규화_인명": canon, "표면형_리스트": set(), "출현_문장_id": set(), "빈도": 0},
        )
        group["표면형_리스트"].add(surface)
        group["출현_문장_id"].add(sent_id)
        group["빈도"] += 1

    result = [
        {
            "정규화_인명": group["정규화_인명"],
            "표면형_리스트": sorted(group["표면형_리스트"]),
            "출현_문장_id": sorted(group["출현_문장_id"]),
            "빈도": group["빈도"],
        }
        for group in groups.values()
    ]
    result.sort(key=lambda g: (-g["빈도"], g["정규화_인명"]))
    return result
