FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B test package

FROM eclipse-temurin:21.0.7_6-jre
RUN groupadd --system --gid 10001 rfcguard && useradd --system --uid 10001 --gid 10001 --home-dir /nonexistent --shell /usr/sbin/nologin rfcguard
WORKDIR /app
COPY --from=build --chown=rfcguard:rfcguard /build/target/sap-rfc-guard-sidecar.jar /app/sap-rfc-guard-sidecar.jar
ENV PORT=8080
USER rfcguard
EXPOSE 8080
HEALTHCHECK --interval=20s --timeout=5s --start-period=15s --retries=3 \
  CMD ["java", "-cp", "/app/sap-rfc-guard-sidecar.jar", "com.logali.rfcguard.HealthCheck"]
ENTRYPOINT ["java", "-Djava.library.path=/opt/sap/jco", "-cp", "/app/sap-rfc-guard-sidecar.jar:/opt/sap/jco/*", "com.logali.rfcguard.Main"]
