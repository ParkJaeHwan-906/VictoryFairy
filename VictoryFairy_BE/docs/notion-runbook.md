# VictoryFairy BE 실행 및 빌드 가이드

## 프로젝트 구조

이 프로젝트는 Gradle 멀티모듈 구조입니다.

| 모듈 | 역할 | 실행 여부 |
| --- | --- | --- |
| `common` | 응답 래퍼·에러 코드 등 외부 의존성 없는 공통 코드 | 실행 모듈 아님 |
| `domain` | Entity, Repository 등 도메인 모듈 | 실행 모듈 아님 |
| `web-support` | JWT 발급/검증 필터, 전역 예외 핸들러, 401 엔트리포인트 | 실행 모듈 아님 |
| `user` | 사용자 기능 애플리케이션 | 실행 가능 |
| `quiz` | 퀴즈 기능 애플리케이션 | 실행 가능 |

실행 가능한 애플리케이션은 `user`, `quiz` 두 개이며 같은 MySQL 을 공유합니다.
`web-support` 가 인증 필터를 양쪽에 함께 넣으므로 두 앱은 **같은 `JWT_SECRET` 을 읽어야** 합니다.
인증 계약의 상세는 [api/README.md](api/README.md) "공통 규약 2·3" 이 단일 출처입니다.

## 포트

| 애플리케이션 | 포트 |
| --- | --- |
| `user` | `8080` |
| `quiz` | `8081` |
| `mysql` | `3306` |

## 로컬 환경 변수

루트 경로의 `.env` 파일을 사용합니다.

필요한 키는 루트 `.env.example` 이 단일 출처입니다 — `cp .env.example .env` 후 값을 채웁니다.
(DB 접속 정보 · `SPRING_PROFILES_ACTIVE` · `JWT_SECRET` · Redis · 메일 발송)

각 실행 모듈은 다음 위치의 설정 파일을 사용합니다.

```text
user/src/main/resources/application.yaml
user/src/main/resources/application-dev.yaml
user/src/main/resources/application-prod.yaml

quiz/src/main/resources/application.yaml
quiz/src/main/resources/application-dev.yaml
quiz/src/main/resources/application-prod.yaml
```

## 프로필 설정

기본 프로필은 `.env`의 `SPRING_PROFILES_ACTIVE` 값으로 결정됩니다.

개발 환경:

```properties
SPRING_PROFILES_ACTIVE=dev
```

운영 환경:

```properties
SPRING_PROFILES_ACTIVE=prod
```

### dev 프로필

`application-dev.yaml`에서 DB 접속 정보와 JPA 개발 설정을 사용합니다.

### prod 프로필

`application-prod.yaml`에서 운영용 JPA 설정을 사용합니다.

## Gradle 빌드

전체 빌드:

```bash
./gradlew clean build
```

개별 모듈 빌드:

```bash
./gradlew :user:build
./gradlew :quiz:build
```

실행 가능한 JAR 생성:

```bash
./gradlew :user:bootJar
./gradlew :quiz:bootJar
```

생성 위치:

```text
user/build/libs/user.jar
quiz/build/libs/quiz.jar
```

## 로컬 실행

### Gradle로 실행

```bash
./gradlew :user:bootRun
./gradlew :quiz:bootRun
```

### JAR로 실행

먼저 JAR를 생성합니다.

```bash
./gradlew :user:bootJar
./gradlew :quiz:bootJar
```

실행:

```bash
java -jar user/build/libs/user.jar
java -jar quiz/build/libs/quiz.jar
```

## IntelliJ IDEA에서 main 실행

각 애플리케이션의 main class를 직접 실행할 수 있습니다.

| 애플리케이션 | Main class | Classpath module |
| --- | --- | --- |
| `user` | `com.skhynix.user.UserApplication` | `VictoryFairy_BE.user.main` |
| `quiz` | `com.skhynix.quiz.QuizApplication` | `VictoryFairy_BE.quiz.main` |

Run Configuration에서 `Use classpath of module` 값이 위 표와 일치해야 합니다.

잘못된 예:

```text
quiz.main
user.main
```

올바른 예:

```text
VictoryFairy_BE.quiz.main
VictoryFairy_BE.user.main
```

IDE 빌드가 실패하면 Gradle Reload를 먼저 수행합니다.

## Docker Compose 실행

전체 컨테이너 실행:

```bash
docker compose up -d --build
```

상태 확인:

```bash
docker compose ps
```

로그 확인:

```bash
docker compose logs -f user
docker compose logs -f quiz
docker compose logs -f mysql
```

중지:

```bash
docker compose down
```

볼륨까지 제거:

```bash
docker compose down -v
```

## Docker 이미지 빌드 방식

Dockerfile은 같은 파일을 사용하고, `MODULE` build arg로 빌드할 모듈을 선택합니다.

```dockerfile
ARG MODULE=user
RUN ./gradlew clean :${MODULE}:bootJar --no-daemon
```

Compose에서는 서비스별로 다른 `MODULE` 값을 전달합니다.

```yaml
user:
  build:
    args:
      MODULE: user

quiz:
  build:
    args:
      MODULE: quiz
```

## 자주 발생하는 문제

### IDE에서 `:classes` 태스크가 없다고 실패하는 경우

루트 프로젝트는 aggregator이므로 원래 Java `classes` 태스크가 없습니다.

현재는 IDE 호환을 위해 루트 `:classes` 태스크가 하위 모듈의 `classes` 태스크를 실행하도록 설정되어 있습니다.

검증:

```bash
./gradlew :classes --stacktrace
```

### 애플리케이션이 바로 종료되는 경우

정상적인 웹 애플리케이션이면 로그에 다음과 같은 메시지가 있어야 합니다.

```text
Tomcat started on port ...
Started ...Application
```

`Started ...Application` 직후 `ShutdownHook` 로그가 나오면 웹 서버가 유지되지 않았거나 실행 classpath가 잘못 잡힌 것입니다.

확인할 것:

```text
Use classpath of module = VictoryFairy_BE.<module>.main
```

### DB 연결 실패

로컬 실행 시 `.env`의 DB 설정을 확인합니다.

```properties
DB_HOST=localhost
DB_PORT=3306
```

Docker Compose 실행 시 애플리케이션 컨테이너는 DB host로 `mysql`을 사용합니다.

```yaml
DB_HOST: mysql
```
