FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY . .
# Windows checkouts may contain CRLF; Maven itself is selected by the committed Wrapper.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw && ./mvnw -B -ntp -pl services/booking-service -am package -DskipTests

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/services/booking-service/target/booking-service-0.0.1-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
