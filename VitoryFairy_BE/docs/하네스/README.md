# 하네스(Harness) 전략

> 목적: **CLAUDE.md 비대화로 인한 중간 context 유지 어려움**을 막기 위해, 컨텍스트를 "항상 로드"에서 "필요할 때만 로드"로 전환한다.
> 최종 업데이트: 2026-06-30

## 핵심 아이디어

큰 단일 CLAUDE.md를 들고 다니는 대신, **작업 단위(모듈)별로 컨텍스트를 쪼개고** 세션 시작 시 어떤 모듈을 작업할지 선택해 **그 모듈 파일 하나만 로드**한다.

```
세션 시작
  └─ SessionStart 훅 실행 → "어떤 모듈?" 묻도록 컨텍스트 주입
       └─ 사용자 첫 요청
            ├─ 모듈이 명확 → 바로 진행
            └─ 불명확 → AskUserQuestion(user/quiz/create/infra)
                 └─ 선택된 .claude/modules/<선택>.md 만 Read → 슬림 컨텍스트로 작업
```

## 구성 요소

| 경로 | 역할 |
|------|------|
| `.claude/settings.json` | SessionStart 훅 등록 (커밋되는 프로젝트 설정) |
| `.claude/hooks/session-start.sh` | 모듈 선택 지침을 `additionalContext`로 주입하는 훅 스크립트 |
| `.claude/modules/user.md` | user 모듈(JWT 인증) 슬림 컨텍스트 |
| `.claude/modules/quiz.md` | quiz 모듈(스켈레톤) 슬림 컨텍스트 |
| `.claude/modules/create.md` | create 모듈(부트스트랩) 슬림 컨텍스트 |
| `.claude/modules/infra.md` | 배포·인프라(EC2→Docker→K8s) 학습/운영 컨텍스트 |

## 동작 원리 (훅)

`session-start.sh`는 stdout으로 아래 형태 JSON을 출력하고, Claude Code가 `additionalContext`를 세션 컨텍스트에 주입한다.

```json
{ "hookSpecificOutput": {
    "hookEventName": "SessionStart",
    "additionalContext": "[모듈 선택 하니스] ... 선택지: user / quiz / create / infra ..." } }
```

- 질문 문구·동작을 바꾸려면 `session-start.sh`의 `additionalContext` 텍스트만 수정.
- 훅은 **다음 세션부터** 발동(SessionStart 특성). 적용하려면 `claude` 재실행 또는 `/hooks` 리로드.

## 모듈 파일 작성 원칙 (컨텍스트 축소)

- **고신호만**: 책임 범위 / 핵심 클래스 / 엔드포인트 / 의존 / 주의점·컨벤션.
- 장황한 코드 인용·자명한 내용 제외.
- 공통 사실(독립 Spring Boot 앱, `:common`·`:domain` 의존, MySQL+dotenv, prod `ddl-auto=none`)은 각 파일 상단 1줄로만 반복.

## 이력 / 결정

- 기존 전역 `~/CLAUDE.md`(EC2→K8s 인프라 학습 일지, 127줄)는 상위 디렉터리 자동로드로 **모든 작업에 항상 주입**되어 비효율 → 내용을 `.claude/modules/infra.md`로 이전하고 전역 파일은 스텁으로 축소. 원본 백업: `C:\Users\doorm\CLAUDE.md.bak`.

## 확장 방법

- 새 작업 영역이 생기면 `.claude/modules/<name>.md` 추가 → `session-start.sh`의 선택지 목록에 `<name>` 추가.
