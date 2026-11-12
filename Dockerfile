# syntax=docker/dockerfile:1.7

FROM maven:3.9.12-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode --no-transfer-progress -DskipTests dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl \
    && addgroup -S bankcore \
    && adduser -S -G bankcore bankcore

WORKDIR /app
COPY --from=build --chown=bankcore:bankcore /workspace/target/bankcore-*.jar /app/bankcore.jar

USER bankcore
EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/bankcore.jar"]
