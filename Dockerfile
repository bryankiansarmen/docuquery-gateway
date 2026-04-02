FROM maven:3.9.11-eclipse-temurin-25-alpine AS build
WORKDIR /sources
COPY /pom.xml /sources/
RUN mvn -B -e -q -Dspring-boot.repackage.skip=true dependency:go-offline
COPY /src/ /sources/src/
RUN mvn clean verify -B -e -q

FROM eclipse-temurin:25-jdk-alpine AS final
WORKDIR /application
COPY --from=build /sources/target/*.jar /application/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-jar", "/application/app.jar"]