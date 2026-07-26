"""크롤러 아티팩트 제거 — 판정 대상 텍스트를 정하는 데만 쓴다.

커뮤니티 앱이 작성 글에 자동으로 붙이는 서명(`- dc official App`)은 **글쓴이가 쓴
내용이 아니라 크롤링 잔여물**이다. 그런데 `str.strip()` 은 이걸 비어 있다고 보지
않으므로, 본문이 서명뿐인 글에서도 "빈 본문 → 제목을 판정" 규칙(PIPE-2SB-8b /
PIPE-S3IO-32~35)이 발동하지 않는다.

실측(dcinside 2026-07-22, 337건)에서 드러난 결과:
  - 본문이 **서명뿐**인 글 15건(4.5%) — 실제 내용은 제목에 있는데 판정되지 않았다
  - Bedrock 폐기 60건 중 **8건(13%)** 이 이 서명을 판정한 것
  - 같은 서명이 `spam` 과 `offtopic` 양쪽으로 갈려 **축 판정이 흔들렸다**
    (`spam` 폐기 3건은 전부 이것이라 실제 spam 표본이 0건이 됐다)

⚠️ **저장되는 본문은 바꾸지 않는다.** 여기서 만든 텍스트는 "무엇을 판정할 것인가"를
정하는 데만 쓰고, S3 success 객체에는 원문을 그대로 남긴다 — 크롤 원본에 대한
충실성을 잃지 않기 위해서다. 서명 자체는 무해한 상용구다.

⚠️ **패턴 러너와 Bedrock 러너가 반드시 같은 판단을 해야 한다.** 두 단계가 서로 다른
텍스트를 판정하면 1차에서 통과시킨 것과 2차가 보는 것이 어긋난다. 그래서 각자
구현하지 않고 이 모듈을 공유한다.
"""

import re
from typing import Optional

# 소스별 자동 서명. 새 소스를 붙이면 여기에 추가한다.
#
# ⚠️ fmkorea 는 **실표본이 0건이라 확인된 바 없다** — 서명이 있는지 없는지 모른다.
# 데이터가 들어오면 마지막 줄 빈발 패턴을 세어 확인할 것(그렇게 이 dcinside 서명을
# 찾았다). 없는 것을 짐작으로 추가하지 마라.
_ARTIFACT_PATTERNS = (
    # dcinside 앱 서명. 실측 337건 중 61건(18%)에 붙어 있었다.
    re.compile(r"^[\s\-—–]*dc\s+official\s+App[\s\-—–]*$", re.IGNORECASE | re.MULTILINE),
)


def strip_crawler_artifacts(text: Optional[str]) -> str:
    """크롤러가 붙인 자동 서명을 지운 텍스트를 돌려준다.

    문자열이 아니면 빈 문자열로 취급한다(크롤 원본에 `null` 이 올 수 있다).
    """
    if not isinstance(text, str):
        return ""
    out = text
    for pattern in _ARTIFACT_PATTERNS:
        out = pattern.sub("", out)
    return out.strip()


def is_blank_content(text: Optional[str]) -> bool:
    """판정할 내용이 실질적으로 없으면 True.

    `str.strip()` 대신 이걸 쓴다 — 서명만 남은 본문은 **내용이 없는 것으로 본다.**
    """
    return not strip_crawler_artifacts(text)
