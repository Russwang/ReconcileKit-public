FROM maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 AS build
WORKDIR /workspace
COPY backend/pom.xml ./pom.xml
COPY backend/src ./src
# Optional build-only Maven proxy credentials/settings never enter the image.
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=secret,id=maven_settings,target=/run/secrets/maven_settings \
    settings=/usr/share/maven/conf/settings.xml; \
    if [ -s /run/secrets/maven_settings ]; then settings=/run/secrets/maven_settings; fi; \
    mvn --settings "$settings" -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-noble@sha256:000fd431958bc81a24abe1e8e5f0f0fd3ae365a594bd50aadb20696805f9408c
RUN groupadd --gid 10001 reconcilekit \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin reconcilekit
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/reconcilekit.jar ./reconcilekit.jar
ENV SERVER_ADDRESS=0.0.0.0 PORT=8080
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/reconcilekit.jar"]
