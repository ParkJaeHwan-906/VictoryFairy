# VictoryFairy AI

FastAPI 기반 AI 서비스 애플리케이션.

## 프로젝트 구조

```
VictoryFairy_AI/
├── app/
│   ├── main.py              # FastAPI 앱 생성 및 진입점
│   ├── core/
│   │   └── config.py        # 환경 설정 (pydantic-settings)
│   ├── api/
│   │   ├── router.py        # API 라우터 통합
│   │   └── routes/
│   │       └── health.py    # 헬스 체크 엔드포인트
│   └── schemas/
│       └── health.py        # Pydantic 응답 스키마
├── requirements.txt
├── .env.example
└── README.md
```

## 실행 방법

```bash
# 1. 가상환경 생성 및 활성화
python3 -m venv .venv
source .venv/bin/activate

# 2. 의존성 설치
pip install -r requirements.txt

# 3. 환경변수 설정 (선택)
cp .env.example .env

# 4. 개발 서버 실행
uvicorn app.main:app --reload
```

서버 실행 후:

- API 문서 (Swagger): http://127.0.0.1:8000/docs
- 헬스 체크: http://127.0.0.1:8000/api/health
