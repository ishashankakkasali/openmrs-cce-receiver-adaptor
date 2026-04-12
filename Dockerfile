# ---- Build stage ----
FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /app

COPY gradle/ gradle/
COPY gradlew build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-jammy

RUN groupadd --system appuser && useradd --system --gid appuser appuser

WORKDIR /app

COPY --from=build /app/build/libs/cce-receiver-adaptor-1.0.0-SNAPSHOT.jar app.jar

RUN chown -R appuser:appuser /app
USER appuser

EXPOSE 8083

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8083/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
