import re
from dataclasses import dataclass


@dataclass(frozen=True)
class TeamRow:
    team_code: str
    name: str
    full_name: str


@dataclass
class PlayerRow:
    player_id: str
    name: str
    back_number: str
    position: str
    throw_bat: str
    birth_date: str | None
    height_cm: int | None
    weight_kg: int | None


@dataclass(frozen=True)
class TradeRow:
    """Trade.aspx(GetTradeList) 한 행. playerId 가 없어 이름+팀으로만 식별된다."""
    date: str            # 'YYYY-MM-DD'
    category: str        # 항목: 트레이드/개명/등번호 변경/웨이버/자유계약선수/...
    team_name: str       # 팀 짧은 이름(트레이드는 도착 팀)
    player_name: str
    position: str | None  # '김한결(투수)' 괄호 안. 없으면 None
    note: str            # 비고: 'KIA→한화', '개명전:박건', '140→62' 등


TEAMS: list[TeamRow] = [
    TeamRow("OB", "두산", "두산 베어스"),
    TeamRow("LG", "LG", "LG 트윈스"),
    TeamRow("SS", "삼성", "삼성 라이온즈"),
    TeamRow("KT", "KT", "KT 위즈"),
    TeamRow("WO", "키움", "키움 히어로즈"),
    TeamRow("HT", "KIA", "KIA 타이거즈"),
    TeamRow("HH", "한화", "한화 이글스"),
    TeamRow("NC", "NC", "NC 다이노스"),
    TeamRow("LT", "롯데", "롯데 자이언츠"),
    TeamRow("SK", "SSG", "SSG 랜더스"),
]
TEAM_CODES: list[str] = [t.team_code for t in TEAMS]

PLAYER_SECTIONS: set[str] = {"투수", "포수", "내야수", "외야수"}

# 등록명단 섹션(=KBO 공식 포지션 구분) -> players.position_group 저장값.
# KBO 공식은 세부 포지션(1루수 등)을 제공하지 않는다(4개 그룹이 전부).
POSITION_GROUPS: dict[str, str] = {
    "투수": "PITCHER",
    "포수": "CATCHER",
    "내야수": "INFIELDER",
    "외야수": "OUTFIELDER",
}

# 이동현황(Trade.aspx) 팀 표기 -> team_code. TEAMS.name 과 동일 표기다.
TEAM_CODE_BY_NAME: dict[str, str] = {t.name: t.team_code for t in TEAMS}


def parse_physique(text: str) -> tuple[int | None, int | None]:
    """'184cm, 88kg' -> (184, 88). 값 없으면 None."""
    h = re.search(r"(\d+)\s*cm", text or "")
    w = re.search(r"(\d+)\s*kg", text or "")
    return (int(h.group(1)) if h else None, int(w.group(1)) if w else None)
