# quiz 모듈

> quiz 작업 시에만 로드되는 슬림 컨텍스트. (공통: `com.skhynix` 독립 앱, `:common`=ApiResponse/BusinessException/ErrorCode, `:domain`=엔티티/리포지토리, MySQL+spring-dotenv, prod `ddl-auto=none`)

## 책임
Quiz 도메인 REST API 서버. **현재 스켈레톤 상태** (컨트롤러 0개, 비즈니스 로직 미구현). **포트 8081.**

## 핵심 클래스 (`quiz/src/main/java/com/skhynix/quiz/`)
- `QuizApplication` — 메인 진입점. `scanBasePackages = "com.skhynix.quiz"`로 **좁게** 스캔 (user/create는 `com.skhynix` 전체). `@EntityScan`/`@EnableJpaRepositories`만 `com.skhynix` 전역.
- `SecurityConfig` — stateless, `@Import(JwtVerificationConfig.class)`로 user의 JWT 검증 부품만 명시적으로 끌어옴(좁은 스캔 탓에 자동 감지 안 됨). `securityFilterChain`이 `JwtAuthenticationFilter`를 직접 `new`로 생성하며 `UserAccountRepository`(`:domain`, uid→id 조회)도 함께 넘김 — user의 필터 생성자가 바뀌면 여기도 같이 고쳐야 함. `/`, `/error`, `GET /health` 외 전부 인증 필수

## 엔드포인트
- **없음** (컨트롤러 0개). SecurityConfig에 `GET /health` permit 규칙은 있으나 이를 처리하는 컨트롤러가 없어 실제로는 **404**.

## 의존
- 모듈: `:common`, `:domain`, **`:user`** (JWT 검증 재사용)
- 라이브러리: JPA, Security, WebMVC, **WebSocket**(실시간 기능 여지), DevTools, JJWT 0.12.6, MySQL, dotenv

## 주의 / 컨벤션
- **JWT_SECRET을 user와 동일하게** (.env 공유) — 불일치 시 토큰 검증 실패
- 응답 `ApiResponse<T>`, 예외 `BusinessException`+`ErrorCode`(common) — **단, `GlobalExceptionHandler`는 `com.skhynix.user.global.error`에 있어 quiz의 좁은 스캔 범위 밖.** quiz에 첫 컨트롤러를 추가해 `BusinessException`을 던지면 `ApiResponse.fail`이 아니라 스프링 기본 500이 나간다 — 필요하면 quiz에도 별도 `@RestControllerAdvice` 추가할 것.
- domain 엔티티는 패키지 스캔으로 자동 로드
- DB 환경변수: `DB_HOST/PORT/NAME/USERNAME/PASSWORD` · dev `ddl-auto=validate`, show-sql ON
