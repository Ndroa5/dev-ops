# syntax=docker/dockerfile:1

FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY user-service/pom.xml user-service/pom.xml
COPY catalog-service/pom.xml catalog-service/pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
COPY e2e-tests/pom.xml e2e-tests/pom.xml
RUN mvn -q -pl notification-service -am dependency:go-offline

COPY notification-service/src notification-service/src
RUN mvn -q -pl notification-service -am clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app
COPY --from=build /build/notification-service/target/notification-service-*.jar app.jar
USER spring
EXPOSE 9084
ENTRYPOINT ["java", "-jar", "app.jar"]
