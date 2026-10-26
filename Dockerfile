# syntax=docker/dockerfile:1

# --- Build stage: compile, extract the layered jar and list the JDK modules it needs ---
# Runs on the build machine's platform: the jar is the same for every target architecture.
# Base images are pinned by digest for reproducible builds; Dependabot proposes updates.
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk@sha256:2d5d5e9b533342a565907c84f47a2ec298c40cde7b13a3ca85d4950fa456a641 AS build
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

# --- JRE stage: a Java runtime with only those modules ---
# Runs on the target platform, because jlink copies that platform's native libraries.
FROM eclipse-temurin:25-jdk@sha256:2d5d5e9b533342a565907c84f47a2ec298c40cde7b13a3ca85d4950fa456a641 AS jre
COPY --from=build /workspace/modules.txt /modules.txt
RUN jlink --add-modules "$(cat /modules.txt)" --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
      --output /jre

# --- Runtime stage: distroless (no shell, no package manager), non-root ---
# java-base has the C library and the few system libraries a JDK needs; the JRE comes from the stage above.
FROM gcr.io/distroless/java-base-debian13:nonroot@sha256:0d16df3717d190692a007d2a0f05a7367f47a35dfdb280016b186b4acf9727f0
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
ENTRYPOINT ["/opt/java/bin/java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]
