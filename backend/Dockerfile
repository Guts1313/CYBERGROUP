# CYBERGROUP IAM backend — Spring Boot 3 / Java 21 OAuth2 resource server.
# Multi-stage: build the fat jar with Maven, then run it on a slim JRE.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/iam-backend-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
