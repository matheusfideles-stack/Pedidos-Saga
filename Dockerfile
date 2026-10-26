# Imagem dos servicos Spring Boot (order-service | inventory-service | payment-service).
#   docker build --build-arg SERVICE=order-service -t saga/order-service .
# Build multi-stage a partir da raiz do monorepo (modulo common e compartilhado).

FROM maven:3.9-eclipse-temurin-21 AS build
ARG SERVICE
WORKDIR /src
COPY pom.xml .
COPY common/pom.xml common/pom.xml
COPY test-support/pom.xml test-support/pom.xml
COPY services/order-service/pom.xml services/order-service/pom.xml
COPY services/inventory-service/pom.xml services/inventory-service/pom.xml
COPY services/payment-service/pom.xml services/payment-service/pom.xml
COPY integration-tests/pom.xml integration-tests/pom.xml
COPY local-stack/pom.xml local-stack/pom.xml
COPY common common
COPY test-support test-support
COPY services services
RUN mvn -B -ntp -q -pl services/${SERVICE} -am package -DskipTests

FROM eclipse-temurin:21-jre
ARG SERVICE
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 app
WORKDIR /app
COPY --from=build /src/services/${SERVICE}/target/${SERVICE}-*-exec.jar app.jar
USER 10001
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
# stateless: nenhuma gravacao em disco; configuracao 100% por variaveis de ambiente
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
