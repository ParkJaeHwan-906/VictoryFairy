# 1단계: Build stage
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

RUN chmod +x ./gradlew

COPY src src

RUN ./gradlew clean bootJar --no-daemon


# 2단계: Runtime stage
FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080

ENV SPRING_PROFILES_ACTIVE=dev

ENTRYPOINT ["sh", "-c", "case \",$SPRING_PROFILES_ACTIVE,\" in *,prod,*) exec java -jar app.jar ;; *) echo \"SPRING_PROFILES_ACTIVE=$SPRING_PROFILES_ACTIVE: skipping Spring application startup\"; exec tail -f /dev/null ;; esac"]
