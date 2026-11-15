FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY . .
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B -ntp -pl services/ticket-service -am package -DskipTests

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/services/ticket-service/target/ticket-service-0.0.1-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
