FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apk add --no-cache maven && mvn -B package -DskipTests --no-transfer-progress

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S acidls && adduser -S acidls -G acidls
COPY --from=builder /app/target/*.jar app.jar
USER acidls
EXPOSE 8090
ENTRYPOINT ["java", "-Xms128m", "-Xmx256m", "-jar", "app.jar"]