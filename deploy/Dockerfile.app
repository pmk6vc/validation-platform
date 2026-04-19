# Build stage
FROM gradle:8.11-jdk21 AS build
WORKDIR /app
COPY --chown=gradle:gradle . .
RUN gradle :app:buildFatJar --no-daemon

# Run stage
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /app/app/build/libs/validation-platform.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
