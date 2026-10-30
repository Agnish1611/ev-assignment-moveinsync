FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /src
COPY . .
RUN chmod +x mvnw && ./mvnw -q -DskipTests package -pl ev-service -am

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/ev-service/target/ev-service-1.0.0-SNAPSHOT-exec.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
