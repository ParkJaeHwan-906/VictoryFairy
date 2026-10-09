# profanity 모듈

> profanity 작업 시에만 로드되는 슬림 컨텍스트. (공통: `com.skhynix` 생태계, 실행 앱 아님 — 포트 없음, `java-library`, 부트 플러그인 미적용, 컨테이너로 안 뜸)

## 책임
욕설 **탐지** 장치와 금지어 데이터를 `quiz`·`chat` 이 공유하는 라이브러리. 구단별 치환어 조립은 여기 없고 소비 앱 몫이다 — quiz `ProfanityFilter`·`MaskWordTable`, chat 은 같은 길이 `*` 마스킹(`maskWithAsterisks`)만 쓴다.

## 핵심 클래스 (`profanity/src/main/java/com/skhynix/profanity/`)
- `ProfanityDetector` — 공개 API. `detect(String): List<ProfanitySpan>` · `maskWithAsterisks(String): String`.
- `ProfanitySpan(int start, int end)` — 원문 기준 매칭 구간(record).
- `ProfanityConfig` — `@Configuration`. 소비 앱의 컴포넌트 스캔 밖이라 **`@Import(ProfanityConfig.class)` 로 등록**한다(quiz 는 `ProfanityFilter` 에, chat 은 `ChatApiRoleConfig` 에).
- 내부: `ProfanityDataLoader`(JSON 4종 로드) · `ProfanityData` · `TextNormalizer`·`TracedText`·`KeyboardMapper`·`ProfanityPatterns`(판정 장치).
- 데이터: `src/main/resources/profanity/` 의 `banned_words`·`exceptions`·`normalization`·`whitespace_strict` `.json`.

## 의존
`spring-context`(구현), `jackson-databind`(`api` — `ProfanityDataLoader` 생성자가 `ObjectMapper` 를 받아 소비 모듈에도 노출). `:common`·`:domain` 의존 없음.

## 주의 / 컨벤션
- **기동 시 JSON 4종을 읽고 패턴을 컴파일하는 fail-fast**: 리소스 누락·형식 위반(예: `single_char` 키 한 글자 제약, 빈 카테고리)이면 빈 생성이 실패해 **소비 앱이 기동 못 한다**(필터가 조용히 꺼진 채 도는 것을 막으려는 의도). chat 은 api 역할이 꺼진 파드에서만 이 로드를 건너뛴다.
- **`VictoryFairy_AI/validation/`(파이썬)과 수동 동기화** — 판정 로직은 그 구현을 이식한 것이고 런타임에 AI 앱을 호출하지 않는다. 금지어 목록·장치가 갈리면 같은 문장이 채팅에서는 걸리고 AI 검증에서는 통과한다. 자동 검증 없음.
- 데이터·로직을 바꾸면 **quiz·chat 둘 다** 영향이다. quiz 의 구단별 치환 후보 순서가 테스트 값을 고정하므로(`quiz.md`) 단어 추가가 quiz 테스트를 깰 수 있다.
- 테스트 19건(`ProfanityDetectorTest`·`ProfanityDataLoaderTest`), 2026-10-09 통과.
