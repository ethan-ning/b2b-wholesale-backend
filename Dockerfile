# One image: the API, with the portal packaged alongside it.
#
# Both are served from the same origin, which is worth more than it sounds. Nothing is
# cross-origin, so no preflight, no CORS list naming a deployment, no second certificate,
# no proxy hop, and one thing to roll back when a release is wrong.
#
# The portal is a separate repository, so its build output is expected at ./frontend-dist.
# `./build-image.sh` puts it there; cloudbuild.yaml does the same in CI. Building without
# it still works — the API runs, and app.serve-spa stays off.

FROM eclipse-temurin:21-jdk AS api
WORKDIR /src

# Build files first, so editing Kotlin does not re-resolve the dependency graph.
COPY gradlew gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY b2b-types/build.gradle.kts          b2b-types/
COPY b2b-domain/build.gradle.kts         b2b-domain/
COPY b2b-application/build.gradle.kts    b2b-application/
COPY b2b-infrastructure/build.gradle.kts b2b-infrastructure/
COPY b2b-web/build.gradle.kts            b2b-web/
COPY b2b-start/build.gradle.kts          b2b-start/
RUN ./gradlew --no-daemon :b2b-start:dependencies --configuration runtimeClasspath > /dev/null 2>&1 || true

COPY b2b-types        b2b-types
COPY b2b-domain       b2b-domain
COPY b2b-application  b2b-application
COPY b2b-infrastructure b2b-infrastructure
COPY b2b-web          b2b-web
COPY b2b-start        b2b-start
# Tests are CI's job. The integration ones need Docker, which this build has no way to
# give them, and a release that quietly skipped them would be worse than one that never
# claimed to run them.
RUN ./gradlew --no-daemon :b2b-start:bootJar -x test


FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --system --uid 10001 --create-home app
USER app

COPY --from=api --chown=app:app /src/b2b-start/build/libs/*-SNAPSHOT.jar app.jar
COPY --chown=app:app frontend-dist/ /app/public/

ENV SPRING_WEB_RESOURCES_STATIC_LOCATIONS=file:/app/public/ \
    APP_SERVE_SPA=true \
    SERVER_PORT=8080
EXPOSE 8080

# MaxRAMPercentage so the container's limit is the limit, rather than a guessed -Xmx that
# is wrong on every instance size but one. exec so the JVM is PID 1 and gets Cloud Run's
# SIGTERM itself — it has ten seconds to shut down and a shell would spend them.
ENTRYPOINT ["sh", "-c", "exec java -XX:MaxRAMPercentage=75 -jar app.jar --server.port=${PORT:-8080}"]
