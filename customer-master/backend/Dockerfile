# Customer Master backend image: one image, two uses.
#
#   app        Compose service `app` runs the default ENTRYPOINT below: the
#              customer-api web application on port 8080. Its Compose
#              healthcheck is `curl -fsS http://localhost:8080/actuator/health/readiness`,
#              which is why the runtime stage carries curl.
#   generator  Compose service `generator` (profile `tools`) runs the same image
#              with the entrypoint overridden to
#              `java -jar /app/app.jar --spring.profiles.active=generator`;
#              `docker compose run` appends the generator flags (--count, --start-id,
#              --csz-file, --seed) and mounts ./data read-only at /data. The jar
#              path /app/app.jar is therefore a contract with docker-compose.yml.
#
# The image replaces two pieces of the original platform. The build stage stands
# in for the editor's compile actions (CRTBNDRPG, CRTSQLRPGI, CRTBNDCL, RUNSQLSTM),
# and the one-shot generator container stands in for the batch job the
# LOADCUST/LOADCUST2 CL programs submitted with SBMJOB: the load runs
# synchronously and reports its outcome through the process exit code.
#
# Build context: customer-master/backend (Compose `build.context: ./backend`).
# .dockerignore keeps every target/ directory out of the context, so the jar is
# always built here and never copied from a host build.
#
# Configuration: none is baked in. Datasource, users, address-client and
# generator settings arrive at run time as environment variables (DB_*, CM_*,
# ADDRESS_VALIDATION_*, USPS_*, SPRING_FLYWAY_LOCATIONS, GENERATOR_*), and
# Compose owns the healthcheck, so this file declares no ENV for the runtime
# stage and no HEALTHCHECK.
#
# BuildKit is required for the cache mount in the build stage. Compose v2 and
# `docker build` on current Docker Engine use it by default. No `# syntax=`
# directive is declared: the built-in Dockerfile frontend supports cache mounts,
# so a build never has to pull a frontend image.

# ---------------------------------------------------------------------------
# Stage 1: build customer-api/target/app.jar with the Maven Wrapper.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21.0.12.1_1-jdk-noble AS build

WORKDIR /build

# The wrapper keeps its Maven distribution under MAVEN_USER_HOME (default
# ~/.m2/wrapper). It is moved out of /root/.m2 so the distribution lands in the
# image layer of the warm-up step below instead of under the cache mount, which
# would hide that layer during the package step. Maven's local repository stays
# at /root/.m2/repository, inside the cache mount.
#
# The cache mount is shared by every concurrent build on the same BuildKit
# daemon, so the resolver takes cross-process file locks on the local
# repository; no build then reads an artifact another build is still writing.
ENV MAVEN_USER_HOME=/opt/maven-wrapper \
    MAVEN_OPTS="-Daether.syncContext.named.factory=file-lock -Daether.syncContext.named.nameMapper=file-gav"

# Layer 1: the wrapper and its Maven 3.9.16 distribution. Only a change to mvnw
# or .mvn/ invalidates it. chmod restores the executable bit a Windows checkout
# or an archive may have lost.
COPY mvnw ./
COPY .mvn/ .mvn/
RUN chmod +x mvnw \
 && ./mvnw -B -ntp -v

# Layer 2: the reactor POMs, in build order. Dependencies are not pre-resolved
# with dependency:go-offline: customer-api depends on the sibling
# address-validation artifact, which exists only once the reactor has built it.
# The cache mount below serves third-party artifacts across builds instead.
COPY pom.xml ./
COPY address-validation/pom.xml address-validation/
COPY customer-api/pom.xml customer-api/

# Layer 3: sources. Test sources are compiled even with -DskipTests, so they
# must compile from this context alone; the OpenAPI snapshot outside it
# (customer-master/openapi/) is read only when OpenApiSnapshotIT runs.
COPY address-validation/src address-validation/src
COPY customer-api/src customer-api/src

# Tests run in the Maven build (./mvnw -B -ntp verify), not in the image build.
# spring-boot-maven-plugin repackages customer-api into the executable
# customer-api/target/app.jar (finalName `app`); app.jar.original is the plain
# jar and is never copied.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -DskipTests package

# ---------------------------------------------------------------------------
# Stage 2: runtime. JRE only, plus curl for the Compose healthcheck.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21.0.12.1_1-jre-noble

LABEL org.opencontainers.image.title="customer-master-app" \
      org.opencontainers.image.description="Customer Master API (default entrypoint) and test-data generator (profile generator)"

# curl is installed explicitly rather than relied on from the base image,
# because the app service's healthcheck invokes it. Its version is left to the
# Ubuntu noble archive on purpose (hadolint DL3008 is waived): the base image
# tag above is pinned exactly, and an apt version pin would break the build as
# soon as a security update supersedes that version in the archive.
# hadolint ignore=DL3008
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Dedicated non-root system account with a fixed numeric id. Ubuntu noble
# already assigns UID/GID 1000 to `ubuntu`; 10001 collides with neither that nor
# the dynamic system range. USER is numeric so orchestrators that enforce
# "run as non-root" can verify it without resolving a name. Nothing is written
# at run time outside /tmp (Tomcat's work directory), and the generator only
# reads /data, which needs world-readable files on the host side of the mount.
RUN groupadd --system --gid 10001 spring \
 && useradd --system --uid 10001 --gid spring --no-create-home \
      --home-dir /nonexistent --shell /usr/sbin/nologin spring

WORKDIR /app

# Root-owned and world-readable: the service account can run the jar but not
# replace it.
COPY --from=build /build/customer-api/target/app.jar /app/app.jar

USER 10001:10001

# server.port default; the nginx /api proxy (app:8080) and k6 (http://app:8080)
# reach the API here.
EXPOSE 8080

# Exec form, no shell wrapper: the JVM is PID 1 and receives SIGTERM from
# `docker stop`, and arguments appended by `docker compose run` (after the
# generator's overridden entrypoint) reach Spring Boot unchanged.
ENTRYPOINT ["java","-jar","/app/app.jar"]
