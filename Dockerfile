# syntax=docker/dockerfile:1

# --- Build stage: compile, extract the layered jar and list the JDK modules it needs ---
# Runs on the build machine's platform: the jar is the same for every target architecture.
# Base images are pinned by digest for reproducible builds; Dependabot proposes updates.
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk@sha256:ce2ef9d2d67a68de5e196d4de9d743327c44a10b57fe02ec39f572c652cacfd3 AS build
WORKDIR /workspace

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
 && java -Djarmode=tools -jar target/*.jar extract --layers --launcher --destination target/extracted
# Modules the code reaches (the libraries on the class path, so their module descriptors are not resolved), plus ones
# only loaded at runtime: Unsafe (Netty, Hibernate), JMX metrics, DNS, SASL and Kerberos (mail and JDBC authentication)
# and zipfs.
RUN jdeps --ignore-missing-deps --multi-release 25 --print-module-deps --recursive \
      --class-path 'target/extracted/dependencies/BOOT-INF/lib/*' target/extracted/application/BOOT-INF/classes \
      > modules.txt \
 && echo "$(cat modules.txt),jdk.unsupported,jdk.management,jdk.naming.dns,java.security.sasl,java.security.jgss,jdk.zipfs" \
      > modules.txt

# --- JDK for jlink, per target architecture ---
# shortcut: Java4Shell (CVE-2026-114938) is fixed in Temurin 25.0.5.1, which the eclipse-temurin image does not ship
# yet. amd64 swaps in the patched JDK; arm64 is not affected (the bug needs AVX-512). Once Dependabot moves the image
# past 25.0.5.1, delete jdk-amd64 and jdk-arm64 and build the jre stage straight from the image again.
FROM eclipse-temurin:25-jdk@sha256:ce2ef9d2d67a68de5e196d4de9d743327c44a10b57fe02ec39f572c652cacfd3 AS jdk-arm64
FROM eclipse-temurin:25-jdk@sha256:ce2ef9d2d67a68de5e196d4de9d743327c44a10b57fe02ec39f572c652cacfd3 AS jdk-amd64
ADD --checksum=sha256:760f91b22c3efc9bd1b64ff950598231be82928cc807e30691a0e7a2995a62c0 \
    https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5.1%2B2/OpenJDK25U-jdk_x64_linux_hotspot_25.0.5.1_2.tar.gz \
    /tmp/jdk.tar.gz
RUN rm -rf "$JAVA_HOME" && mkdir "$JAVA_HOME" \
 && tar -xzf /tmp/jdk.tar.gz -C "$JAVA_HOME" --strip-components=1 && rm /tmp/jdk.tar.gz

# --- JRE stage: a Java runtime with only those modules ---
# Runs on the target platform, because jlink copies that platform's native libraries.
FROM jdk-${TARGETARCH} AS jre
COPY --from=build /workspace/modules.txt /modules.txt
RUN jlink --add-modules "$(cat /modules.txt)" --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
      --output /jre

# --- Runtime stage: distroless (no shell, no package manager), non-root ---
# java-base has the C library and the few system libraries a JDK needs; the JRE comes from the stage above.
FROM gcr.io/distroless/java-base-debian13:nonroot@sha256:4b56aef44dee352ec8321ac447db1592909c029183a41be1c41375ca8b936549
COPY --from=jre /jre /opt/java
# A numeric user lets Kubernetes verify runAsNonRoot (see deploy/kubernetes).
USER 10001:10001
WORKDIR /app

# Least frequently changing layers first, for better caching.
COPY --from=build /workspace/target/extracted/dependencies/ ./
COPY --from=build /workspace/target/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/target/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/target/extracted/application/ ./

# 8080: API. 8081: Actuator with SPRING_PROFILES_ACTIVE=prod (keep it internal).
# No HEALTHCHECK: the image has no shell or HTTP client; Kubernetes uses the probes in deploy/kubernetes.
EXPOSE 8080 8081
# ExitOnOutOfMemoryError: a JVM that ran out of heap is left half-broken, and liveness is served by the separate
# management port, so it might never be restarted. Exiting lets the orchestrator restart it.
ENTRYPOINT ["/opt/java/bin/java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
