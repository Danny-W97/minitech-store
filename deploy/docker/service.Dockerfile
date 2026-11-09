# Build one independently deployable Spring Boot service from the monorepo.
# Example:
# docker build -f deploy/docker/service.Dockerfile \
#   --build-arg SERVICE_MODULE=product-service \
#   -t mini-order-platform/product-service:local .

FROM maven:3.9-eclipse-temurin-25@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976 AS build

WORKDIR /workspace
ARG SERVICE_MODULE

COPY pom.xml mvnw ./
COPY .mvn .mvn
COPY platform-security platform-security
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY product-service/pom.xml product-service/pom.xml
COPY order-service/pom.xml order-service/pom.xml
COPY inventory-service/pom.xml inventory-service/pom.xml
COPY payment-service/pom.xml payment-service/pom.xml
COPY notification-service/pom.xml notification-service/pom.xml
COPY customer-service/pom.xml customer-service/pom.xml
COPY delivery-service/pom.xml delivery-service/pom.xml
COPY promotion-service/pom.xml promotion-service/pom.xml
COPY wishlist-service/pom.xml wishlist-service/pom.xml
COPY review-service/pom.xml review-service/pom.xml
COPY recommendation-service/pom.xml recommendation-service/pom.xml
COPY chat-service/pom.xml chat-service/pom.xml
COPY returns-service/pom.xml returns-service/pom.xml
COPY billing-service/pom.xml billing-service/pom.xml
COPY qa-tests/pom.xml qa-tests/pom.xml

COPY ${SERVICE_MODULE}/src ${SERVICE_MODULE}/src
# Reviewed non-secret export policy, packaged only by Customer's explicit resource include.
COPY scripts/qa/contracts/technical-data-classification.json scripts/qa/contracts/technical-data-classification.json

RUN --mount=type=cache,target=/root/.m2/repository \
    chmod +x mvnw \
    && for attempt in 1 2 3 4; do mvn --batch-mode -pl "${SERVICE_MODULE}" -am \
       -DskipTests -DskipITs -Dmaven.test.skip=true package && break; if [ "$attempt" -eq 4 ]; then exit 1; fi; sleep $((attempt * 5)); done \
    && find "/workspace/${SERVICE_MODULE}/target" -maxdepth 1 -type f \
       -name '*-SNAPSHOT.jar' ! -name '*.original' -exec cp {} /tmp/app.jar \; \
    && test -s /tmp/app.jar

FROM eclipse-temurin:25-jre@sha256:ecdc0adb37bde1a281c4d245a3cd535004b1fc8322d560f5b09463b248c81df0

USER root
# Fonts are part of the reviewed runtime digest. Fail instead of changing packages
# from mutable apt indexes or silently producing PDFs without the expected font.
RUN test -s /usr/share/fonts/truetype/dejavu/DejaVuSans.ttf \
    && test -s /usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf \
    && rm -f /usr/bin/pebble

WORKDIR /app
COPY --from=build /tmp/app.jar /app/app.jar

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp -Duser.home=/tmp"
EXPOSE 8080
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
