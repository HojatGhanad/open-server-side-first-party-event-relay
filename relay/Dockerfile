# syntax=docker/dockerfile:1
# Multi-stage build for the relay (and the panel, which runs from the same image).
#
# REGISTRY lets you pull base images through a Docker Hub mirror, e.g.
#   docker build --build-arg REGISTRY=mirror.example.com/ .
ARG REGISTRY=

FROM ${REGISTRY}maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM ${REGISTRY}eclipse-temurin:21-jre-alpine

LABEL org.opencontainers.image.title="fp-relay" \
      org.opencontainers.image.description="Open-source first-party relay for server-side Google Tag Manager" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.source="https://github.com/HojatGhanad/open-server-side-first-party-event-relay"

RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build --chown=app:app /src/target/fp-relay.jar app.jar
USER app

EXPOSE 8090 8091

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
