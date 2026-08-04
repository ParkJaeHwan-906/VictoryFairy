# 1단계: Build stage
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

RUN chmod +x ./gradlew

COPY common common
COPY domain domain
COPY web-support web-support
COPY user user
COPY quiz quiz
# user/build.gradle 의 processResources 가 infra/sql 의 시드 SQL 을 jar 클래스패스로 복사한다
# (user prod 의 spring.sql.init 이 classpath:sql/*.sql 로 읽는다).
# ⚠ 이 줄을 빼면 빌드는 성공하고 jar 에서 sql 만 빠진다 — Gradle 의 from() 은 없는 디렉터리를
#   조용히 무시하기 때문이다. 그 이미지는 기동 시 "명시한 스크립트를 못 찾음"으로 컨텍스트가
#   깨져 파드가 Ready 가 되지 않는다(2026-08-04 롤아웃 실패의 원인).
COPY infra infra

ARG MODULE=user
RUN ./gradlew clean :${MODULE}:bootJar --no-daemon


# 2단계: Runtime stage
FROM eclipse-temurin:21-jre

WORKDIR /app

ARG MODULE=user
COPY --from=builder /app/${MODULE}/build/libs/*.jar app.jar

EXPOSE 8080
EXPOSE 8081

ENV SPRING_PROFILES_ACTIVE=dev

ENTRYPOINT ["java", "-jar", "app.jar"]
