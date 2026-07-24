# quiz 모듈

> quiz 작업 시에만 로드되는 슬림 컨텍스트. (공통: `com.skhynix` 독립 앱, `:common`=ApiResponse/BusinessException/ErrorCode, `:domain`=엔티티/리포지토리, MySQL+spring-dotenv, prod `ddl-auto=none`)

## 책임
Quiz 도메인 REST API 서버. **현재 스켈레톤 상태** (health check만, 비즈니스 로직 미구현). **포트 8081.**

## 핵심 클래스 (`quiz/src/main/java/com/skhynix/quiz/`)
- `QuizApplication` — 메인 진입점 (`com.skhynix` 전체 스캔)
- `SecurityConfig` — stateless, user 모듈의 JWT 검증 설정 재사용. `/`, `/error`, `GET /health` 외 전부 인증 필수

## 엔드포인트
- 아직 없음 (health only)

## 의존
- 모듈: `:common`, `:domain`, **`:user`** (JWT 검증 재사용)
- 라이브러리: JPA, Security, WebMVC, **WebSocket**(실시간 기능 여지), DevTools, JJWT 0.12.6, MySQL, dotenv

## 주의 / 컨벤션
- **JWT_SECRET을 user와 동일하게** (.env 공유) — 불일치 시 토큰 검증 실패
- 응답 `ApiResponse<T>`, 예외 `BusinessException`+`ErrorCode`(common)
- domain 엔티티는 패키지 스캔으로 자동 로드
- DB 환경변수: `DB_HOST/PORT/NAME/USERNAME/PASSWORD` · dev `ddl-auto=validate`, show-sql ON
