# VictoryFairy BE 실행 및 빌드 가이드

## 프로젝트 구조

이 프로젝트는 Gradle 멀티모듈 구조입니다.

| 모듈 | 역할 | 실행 여부 |
| --- | --- | --- |
| `common` | 공통 코드 모듈 | 실행 모듈 아님 |
| `domain` | Entity, Repository 등 도메인 모듈 | 실행 모듈 아님 |
| `user` | 사용자 기능 애플리케이션 | 실행 가능 |
| `quiz` | 퀴즈 기능 애플리케이션 | 실행 가능 |
| `create` | 생성 기능 애플리케이션 | 실행 가능 |

실행 가능한 애플리케이션은 `user`, `quiz`, `create` 세 개입니다.

## 포트

| 애플리케이션 | 포트 |
| --- | --- |
| `user` | `8080` |
| `quiz` | `8081` |
| `create` | `8082` |
| `mysql` | `3306` |

## 로컬 환경 변수

루트 경로의 `.env` 파일을 사용합니다.

필요한 키:

```properties
DB_HOST=localhost
DB_PORT=3306
DB_NAME=...
DB_USERNAME=...
DB_PASSWORD=...
SPRING_PROFILES_ACTIVE=dev
```

`application.yaml` 파일은 Git 추적 대상에서 제외되어 있습니다.

각 실행 모듈은 다음 위치의 설정 파일을 사용합니다.

```text
user/src/main/resources/application.yaml
user/src/main/resources/application-dev.yaml
user/src/main/resources/application-prod.yaml

quiz/src/main/resources/application.yaml
quiz/src/main/resources/application-dev.yaml
quiz/src/main/resources/application-prod.yaml

create/src/main/resources/application.yaml
create/src/main/resources/application-dev.yaml
create/src/main/resources/application-prod.yaml
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

주요 설정:

```yaml
spring:
  datasource:
    url: jdbc:mysql://${DB_HOST}:${DB_PORT}/${DB_NAME}?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
    driver-class-name: com.mysql.cj.jdbc.Driver

  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true
    properties:
      hibernate:
        format_sql: true
```

### prod 프로필

`application-prod.yaml`에서 운영용 JPA 설정을 사용합니다.

주요 설정:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: none
    show-sql: false
    properties:
      hibernate:
        format_sql: false
    open-in-view: false
```

## Gradle 빌드

전체 빌드:

```bash
./gradlew clean build
```

개별 모듈 빌드:

```bash
./gradlew :user:build
./gradlew :quiz:build
./gradlew :create:build
```

실행 가능한 JAR 생성:

```bash
./gradlew :user:bootJar
./gradlew :quiz:bootJar
./gradlew :create:bootJar
```

생성 위치:

```text
user/build/libs/user.jar
quiz/build/libs/quiz.jar
create/build/libs/create.jar
```

## 로컬 실행

### Gradle로 실행

```bash
./gradlew :user:bootRun
./gradlew :quiz:bootRun
./gradlew :create:bootRun
```

### JAR로 실행

먼저 JAR를 생성합니다.

```bash
./gradlew :user:bootJar
./gradlew :quiz:bootJar
./gradlew :create:bootJar
```

실행:

```bash
java -jar user/build/libs/user.jar
java -jar quiz/build/libs/quiz.jar
java -jar create/build/libs/create.jar
```

## IntelliJ IDEA에서 main 실행

각 애플리케이션의 main class를 직접 실행할 수 있습니다.

| 애플리케이션 | Main class | Classpath module |
| --- | --- | --- |
| `user` | `com.skhynix.user.UserApplication` | `VitoryFairy_BE.user.main` |
| `quiz` | `com.skhynix.quiz.QuizApplication` | `VitoryFairy_BE.quiz.main` |
| `create` | `com.skhynix.create.CreateApplication` | `VitoryFairy_BE.create.main` |

Run Configuration에서 `Use classpath of module` 값이 위 표와 일치해야 합니다.

잘못된 예:

```text
quiz.main
user.main
create.main
```

올바른 예:

```text
VitoryFairy_BE.quiz.main
VitoryFairy_BE.user.main
VitoryFairy_BE.create.main
```

IDE 빌드가 실패하면 Gradle Reload를 먼저 수행합니다.

## Docker Compose 실행

전체 컨테이너 실행:

```bash
docker compose up -d --build
```

실행되는 컨테이너:

| 컨테이너 | 서비스 | 포트 |
| --- | --- | --- |
| `victoryfairy-mysql` | `mysql` | `3306` |
| `victoryfairy-user` | `user` | `8080` |
| `victoryfairy-quiz` | `quiz` | `8081` |
| `victoryfairy-create` | `create` | `8082` |

상태 확인:

```bash
docker compose ps
```

로그 확인:

```bash
docker compose logs -f user
docker compose logs -f quiz
docker compose logs -f create
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

create:
  build:
    args:
      MODULE: create
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
Use classpath of module = VitoryFairy_BE.<module>.main
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

## Git 관리 규칙

다음 파일은 로컬 설정 파일이므로 Git에 포함하지 않습니다.

```text
user/src/main/resources/application.yaml
user/src/main/resources/application-*.yaml
quiz/src/main/resources/application.yaml
quiz/src/main/resources/application-*.yaml
create/src/main/resources/application.yaml
create/src/main/resources/application-*.yaml
.env
.env.*
```
