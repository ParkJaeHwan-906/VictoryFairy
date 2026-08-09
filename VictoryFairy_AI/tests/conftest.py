import sys
from pathlib import Path

_ROOT = Path(__file__).resolve().parents[1]
# routine 스크립트(question-gen/wiki-builder — 하이픈 디렉토리라 패키지 불가)를
# 모듈로 import 가능하게 한다.
for _p in ("question-gen/scripts", "wiki-builder/scripts"):
    sys.path.insert(0, str(_ROOT / _p))
