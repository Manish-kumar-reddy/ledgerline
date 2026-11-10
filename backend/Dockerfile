# Production image for the LedgerLine backend (Render, or any container host).
# Build context: this backend/ directory. No secrets are baked in: all configuration comes from environment
# variables at run time (see README.md, "Deployment").

# ---- build stage: compile and package the existing Maven project (tests are run separately, before deploying) ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline -Dmaven.test.skip=true || true
COPY src/main/ src/main/
RUN ./mvnw -B -q -Dmaven.test.skip=true package \
 && cp target/ledgerline-backend-0.0.1-SNAPSHOT.jar /build/app.jar

# ---- runtime stage: Java 21 JRE only, non-root ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S ledgerline && adduser -S -G ledgerline -H -s /sbin/nologin ledgerline
WORKDIR /app
COPY --from=build --chown=root:root /build/app.jar /app/app.jar
COPY --chown=root:root docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod 0555 /app/docker-entrypoint.sh && chmod 0444 /app/app.jar
USER ledgerline

# Conservative memory for small instances: the heap may use 75% of the container limit; Serial GC has the lowest
# overhead on a fraction of a CPU. BCrypt cost and all security settings are unchanged.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["/app/docker-entrypoint.sh"]
