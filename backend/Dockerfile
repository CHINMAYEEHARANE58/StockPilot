FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -Dmaven.test.skip=true package
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/stockpilot-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
