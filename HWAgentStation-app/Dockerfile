FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY . .
RUN mvn -B -DskipTests -pl HWAgentStation-app -am package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/HWAgentStation-app/target/HWAgentStation-app.jar /app/app.jar
EXPOSE 8091
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
