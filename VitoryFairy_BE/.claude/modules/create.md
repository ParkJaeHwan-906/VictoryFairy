# create 모듈

> create 작업 시에만 로드되는 슬림 컨텍스트. (공통: `com.skhynix` 독립 앱, `:common`=ApiResponse/BusinessException/ErrorCode, `:domain`=엔티티/리포지토리, MySQL+spring-dotenv, prod `ddl-auto=none`)

## 책임
부트스트랩 진입점만 담당. **자체 기능/엔드포인트 없음.** **포트 8082.**

## 핵심 클래스 (`create/src/main/java/com/skhynix/...`)
- `CreateApplication` — 유일한 클래스. `@SpringBootApplication(scanBasePackages="com.skhynix")` + `@EntityScan` + `@EnableJpaRepositories`

## 의존
- 모듈: `:common`, `:domain` (역방향 의존 없음)
- 라이브러리: starter, JPA, WebMVC, MySQL, dotenv

## 주의 / 컨벤션
- 새 기능은 create가 아니라 **해당 도메인 모듈에 추가** (create는 부트스트랩 전용)
- DB 환경변수: `DB_HOST/PORT/NAME/USERNAME/PASSWORD` · dev `ddl-auto=validate`, prod `none`/`open-in-view=false`
