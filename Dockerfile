FROM gradle:9.5.1-jdk17@sha256:1d769ab80e82b12346e106ad8536a8f659d02167089bebef44448311ee6fb16f AS builder

WORKDIR /workspace
COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle settings.gradle ./
COPY src src

RUN sh ./gradlew clean bootJar --no-daemon \
    && find build/libs -maxdepth 1 -type f -name '*-SNAPSHOT.jar' -exec cp '{}' /tmp/app.jar \;

FROM eclipse-temurin:17-jdk-jammy@sha256:ef4374b4b6b9d813dd3f5b593a35ec9a820cfb64a55994798147cc73435a0208

RUN apt-get update \
    && apt-get install --no-install-recommends --yes curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --home-dir /home/appuser --shell /usr/sbin/nologin appuser

WORKDIR /app
COPY --from=builder --chown=appuser:appuser /tmp/app.jar /app/app.jar

USER appuser
ARG SERVER_PORT=8080
EXPOSE ${SERVER_PORT}
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
