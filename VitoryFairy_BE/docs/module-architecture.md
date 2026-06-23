# 모듈 구조 및 인증 아키텍처

VictoryFairy BE는 Gradle 멀티모듈 프로젝트입니다. 이 문서는 **모듈별 역할**, **모듈 간 의존성**, **인증(JWT) 적용 방향**을 정리합니다.

## 모듈별 역할

| 모듈 | 타입 | 실행 | 포트 | 역할 |
| --- | --- | --- | --- | --- |
| `common` | 라이브러리 (`java-library`) | X | - | 전 모듈 공통 유틸리티. 외부 의존성 없는 순수 공통 코드 |
| `domain` | 라이브러리 (`java-library`) | X | - | JPA 엔티티 · 리포지토리. 데이터 모델의 단일 소유자 |
| `user` | 애플리케이션 (Spring Boot) | O | 8080 | 사용자 도메인. **인증 토큰 발급(회원가입/로그인)** + 검증 |
| `quiz` | 애플리케이션 (Spring Boot) | O | 8081 | 퀴즈 도메인. **인증 토큰 검증**(user의 검증 부품 재사용) |
| `create` | 애플리케이션 (Spring Boot) | O | 8082 | 생성 도메인. **인증 없음** |

실행 가능한 애플리케이션은 `user`, `quiz`, `create` 세 개이며, 세 앱은 **같은 MySQL DB를 공유**합니다.

## 모듈 의존성

```
        common  (의존성 없음)
          ▲
          │
        domain  (← common, spring-data-jpa)
          ▲
   ┌──────┼──────────────┐
   │      │              │
 user    quiz          create
          │
          └── user 의존 (JWT 검증 부품 재사용)
```

- `common ← domain ← {user, quiz, create}` : 모든 실행 모듈은 `common`, `domain`을 의존
- `user ← quiz` : quiz가 user를 의존 (JWT 검증 인프라 재사용 목적)
- `create`는 어떤 실행 모듈도 의존하지 않으며, 보안 의존성 자체가 없음

### 모듈별 주요 의존성

| 모듈 | 주요 의존성 |
| --- | --- |
| `common` | (없음) |
| `domain` | `common`, `spring-boot-starter-data-jpa` |
| `user` | `common`, `domain`, security, webmvc, validation, **jjwt**, mysql, dotenv |
| `quiz` | `common`, `domain`, **`user`**, security, webmvc, websocket, mysql, dotenv |
| `create` | `common`, `domain`, starter, data-jpa, webmvc, mysql, dotenv (**security 없음**) |

## 인증(JWT) 방향

인증은 **토큰 발급**과 **토큰 검증**으로 나뉩니다.

| 모듈 | 토큰 발급 (login/signup) | 토큰 검증 (요청 인증) |
| --- | --- | --- |
| `user` | O | O |
| `quiz` | X | O |
| `create` | X | X |

### 발급은 user, 검증은 공유

```
[user 모듈]
  AuthController/AuthService        → 토큰 발급 (user 전용)
  global/jwt/JwtVerificationConfig  → 토큰 검증 부품 (공유 대상)
        │  JwtProperties / JwtTokenProvider / JwtAuthenticationFilter
        │
        └──(@Import)──▶ [quiz 모듈] SecurityConfig
                          quiz는 검증 부품만 가져와 필터 장착
                          (발급 로직 AuthController 등은 따라오지 않음)
```

- **발급 로직**(`AuthController`, `AuthService`, DTO)은 `user`에만 존재
- **검증 인프라**는 `user/global/jwt`의 `JwtVerificationConfig`에 격리되어 있고, `quiz`는 `@Import(JwtVerificationConfig.class)`로 **검증 부품만** 재사용
- `quiz`는 컴포넌트 스캔을 `com.skhynix.quiz`로 좁혀, user의 발급 엔드포인트(`/api/auth/**`)가 quiz에 노출되지 않도록 함

### 토큰 흐름

```
1. 클라이언트 → user(8080) /api/auth/login → access + refresh 토큰 발급
2. 클라이언트 → quiz(8081) 보호 API + Authorization: Bearer <access>
3. quiz의 JwtAuthenticationFilter가 토큰 검증 → 인증 통과
```

- access 토큰만 요청 인증에 사용 (refresh 토큰으로는 API 접근 불가)
- 토큰 서명/검증은 HS256. **user와 quiz가 동일한 `JWT_SECRET`을 공유**해야 검증 성립
- `JWT_SECRET`은 루트 `.env`에 두고 두 앱이 함께 읽음

### 토큰 정책

- **refresh 회전**: 재발급 시 사용한 refresh 토큰을 만료시키고 새 토큰 발급
- **유저당 유효 refresh 1개**: 새 로그인/재발급 시 기존 유효 토큰을 즉시 만료
- 토큰마다 `jti`(UUID)를 부여해 같은 시각 발급 토큰도 항상 유일

## 설정 파일 관리

- 각 실행 모듈의 `src/main/resources/application*.yaml`은 **gitignore 대상**(로컬 전용)
- DB 접속 정보, `JWT_SECRET` 등 민감 설정은 루트 `.env`로 주입
- 따라서 새 환경에서는 `.env`와 각 모듈의 `application.yaml`을 직접 구성해야 함

자세한 실행/빌드 방법은 [notion-runbook.md](./notion-runbook.md) 참고.
