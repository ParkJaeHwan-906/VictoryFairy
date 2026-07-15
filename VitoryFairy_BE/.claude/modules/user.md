# user 모듈

> user 작업 시에만 로드되는 슬림 컨텍스트. (공통: `com.skhynix` 독립 앱, `:common`=ApiResponse/BusinessException/ErrorCode, `:domain`=엔티티/리포지토리, MySQL+spring-dotenv, prod `ddl-auto=none`)

## 책임
JWT 기반 인증/인가. signup·login·refresh·logout. **포트 8080.**

## 핵심 클래스 (`user/src/main/java/com/skhynix/...`)
- `AuthController` → `/api/auth/*`
- `AuthService` — 가입/로그인/재발급/로그아웃 로직
- `SecurityConfig` — stateless + JWT 필터 통합
- `JwtTokenProvider` (HS256 생성/검증), `JwtAuthenticationFilter` (Bearer 파싱), `JwtProperties`
- `GlobalExceptionHandler` (`@RestControllerAdvice`)
- DTO는 전부 **record**: `SignupRequest`, `LoginRequest`, `TokenRequest/Response` (`SignupResponse`는 `AuthController`에 import만 되어 있고 미사용 — signup은 `ResponseEntity<Boolean>` 반환)

## 엔드포인트
```
POST /api/auth/signup    SignupRequest → Boolean (201)
POST /api/auth/login     LoginRequest  → TokenResponse
POST /api/auth/refresh   TokenRequest  → TokenResponse
POST /api/auth/logout    TokenRequest  → 204
```

## 의존
- 모듈: `:common`, `:domain`
- 라이브러리: Security, JPA, Validation, JJWT, BCrypt, MySQL, dotenv
- 엔티티는 **domain에 위치**: `User`, `UserAccount`, `UserRefreshToken` (user는 Repository만 주입)

## 주의 / 컨벤션
- 토큰 claim `type: access|refresh` → `isRefreshToken()`으로 구분
- **Refresh 토큰 1개 정책**: 발급 직전 `expireValidTokens()`로 기존 유효 토큰 만료(재사용 방지)
- 비번: 저장 `passwordEncoder.encode()`, 검증 `matches()`
- 설정: `jwt.secret`(32B+), access 3h(10800000ms) / refresh 14d(1209600000ms)
- DB 환경변수: `DB_HOST/PORT/NAME/USERNAME/PASSWORD` · dev `ddl-auto=create`
