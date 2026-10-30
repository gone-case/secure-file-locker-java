# Build stage: compiles and runs the tests (skip tests with --build-arg SKIP_TESTS=true)
FROM maven:3.9-eclipse-temurin-21 AS build
ARG SKIP_TESTS=false
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src src
RUN mvn -B -q package -DskipTests=${SKIP_TESTS}

# Runtime stage: JRE only, non-root user
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /app/target/secure-file-locker-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
