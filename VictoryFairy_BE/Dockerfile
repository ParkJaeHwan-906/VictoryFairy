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
