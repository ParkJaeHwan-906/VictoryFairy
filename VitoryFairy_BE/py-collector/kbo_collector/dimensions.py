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


def parse_physique(text: str) -> tuple[int | None, int | None]:
    """'184cm, 88kg' -> (184, 88). 값 없으면 None."""
    h = re.search(r"(\d+)\s*cm", text or "")
    w = re.search(r"(\d+)\s*kg", text or "")
    return (int(h.group(1)) if h else None, int(w.group(1)) if w else None)
