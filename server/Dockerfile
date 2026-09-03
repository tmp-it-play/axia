# syntax=docker/dockerfile:1.7
ARG JDK_IMAGE=eclipse-temurin:21-jdk-jammy@sha256:ce5767b7222312d42395f5bab033cd91f09e44032a2f21bdfd7b5b912dbe1e77
ARG JRE_IMAGE=eclipse-temurin:21-jre-jammy@sha256:eebd356ad7358b7094758e5787a6726f332917cfd56feab6457c56dab895cdbf

FROM ${JDK_IMAGE} AS build
WORKDIR /workspace
COPY . .
RUN chmod 0755 gradlew
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon --no-configuration-cache --max-workers=2 \
    -Dorg.gradle.jvmargs=-Xmx2g -Pkotlin.compiler.execution.strategy=in-process :server:installDist

FROM ${JRE_IMAGE} AS runtime
RUN apt-get update \
    && apt-get install --yes --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 axia \
    && useradd --uid 10001 --gid axia --no-create-home --shell /usr/sbin/nologin axia \
    && install -d -o axia -g axia /var/log/axia
WORKDIR /opt/axia
COPY --from=build --chown=root:root /workspace/server/build/install/server/ ./
COPY --chown=root:root deploy/server/logback.xml /opt/axia/config/logback.xml
COPY --chown=root:root --chmod=0755 deploy/server/healthcheck.sh /opt/axia/bin/healthcheck
COPY --chown=root:root --chmod=0755 deploy/server/entrypoint.sh /opt/axia/bin/entrypoint
ENV AXIA_SERVER_HOST=0.0.0.0 \
    AXIA_SERVER_PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Dlogback.configurationFile=/opt/axia/config/logback.xml"
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 CMD ["/opt/axia/bin/healthcheck"]
ENTRYPOINT ["/opt/axia/bin/entrypoint"]
