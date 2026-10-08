# Customer Master backend image. Compose `app` runs the default ENTRYPOINT
# (customer-api on port 8080) and `generator` overrides the entrypoint to add
# --spring.profiles.active=generator, so /app/app.jar is a contract with
# docker-compose.yml. Compose also supplies the runtime configuration and the
# healthcheck, so the image bakes in neither. BuildKit's built-in Dockerfile
# frontend supports the cache mount below, so no `# syntax=` directive (and no
# frontend image pull) is needed.

FROM eclipse-temurin:21.0.12.1_1-jdk-noble AS build

WORKDIR /build

# unzip makes mvnw download the ZIP whose SHA-256 maven-wrapper.properties
# pins; without it mvnw falls back to the .tar.gz, which fails that check. Its
# version is left to the archive, as for curl in the runtime stage.
# hadolint ignore=DL3008
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends unzip \
 && rm -rf /var/lib/apt/lists/*

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

# chmod restores the executable bit a Windows checkout or an archive may have
# lost.
COPY mvnw ./
COPY .mvn/ .mvn/
RUN chmod +x mvnw \
 && ./mvnw -B -ntp -v

# Dependencies are not pre-resolved with dependency:go-offline: customer-api
# depends on the sibling address-validation artifact, which exists only once the
# reactor has built it. The cache mount below serves third-party artifacts
# across builds instead.
COPY pom.xml ./
COPY address-validation/pom.xml address-validation/
COPY customer-api/pom.xml customer-api/

# Test sources compile even with -DskipTests, so they must build from this
# context alone.
COPY address-validation/src address-validation/src
COPY customer-api/src customer-api/src

RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -DskipTests package

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

# Non-root account with fixed id 10001, clear of the `ubuntu` user's 1000 and
# the dynamic system range; USER is numeric so runAsNonRoot checks need no name
# lookup. Only /tmp (Tomcat's work directory) is written at run time, and the
# generator reads the /data mount as uid 10001, so host files there must be
# world-readable.
RUN groupadd --system --gid 10001 spring \
 && useradd --system --uid 10001 --gid spring --no-create-home \
      --home-dir /nonexistent --shell /usr/sbin/nologin spring

WORKDIR /app

# Root-owned and world-readable: the service account can run the jar but not
# replace it.
COPY --from=build /build/customer-api/target/app.jar /app/app.jar

USER 10001:10001

EXPOSE 8080

# Exec form, no shell: the JVM is PID 1 and receives SIGTERM from `docker stop`,
# and arguments appended at run time, such as the generator's flags after its
# Compose entrypoint override, reach Spring Boot unchanged.
ENTRYPOINT ["java","-jar","/app/app.jar"]
