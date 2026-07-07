# syntax=docker/dockerfile:1

FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy the reactor's poms first (module list resolution + dependency layer caching)
COPY pom.xml .
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY user-service/pom.xml user-service/pom.xml
COPY catalog-service/pom.xml catalog-service/pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
COPY e2e-tests/pom.xml e2e-tests/pom.xml
RUN mvn -q -pl api-gateway -am dependency:go-offline

COPY api-gateway/src api-gateway/src
RUN mvn -q -pl api-gateway -am clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S spring && adduser -S spring -G spring
WORKDIR /app
COPY --from=build /build/api-gateway/target/api-gateway-*.jar app.jar
USER spring
EXPOSE 9080
ENTRYPOINT ["java", "-jar", "app.jar"]
