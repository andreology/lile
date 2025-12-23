# syntax=docker/dockerfile:1.6

ARG BUILDER_IMAGE=gradle:8-jdk17-jammy
ARG RUNTIME_IMAGE=eclipse-temurin:17-jre-jammy
ARG DEBUG_DIAG=false

FROM ${BUILDER_IMAGE} AS build
WORKDIR /workspace

# Copy everything so the Gradle build has its wrapper, settings, and sources.
COPY . .

# Build the Spring Boot fat jar with Gradle (wrapper if present, else global).
RUN --mount=type=cache,target=/home/gradle/.gradle \
    if [ -x ./gradlew ]; then \
      ./gradlew --no-daemon -x test bootJar; \
    else \
      gradle --no-daemon -x test bootJar; \
    fi

FROM ${RUNTIME_IMAGE} AS runtime
ENV LANG=en_US.UTF-8 \
    LC_ALL=en_US.UTF-8 \
    TESSDATA_PREFIX=/opt/tessdata \
    FORM_PROCESSING_TESS_DATA_PATH=/opt/tessdata \
    JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"

# Bundle the vetted tessdata used at build time so runtime matches local/CI.
COPY src/main/resources/tessdata /opt/tessdata
COPY src/main/resources/eng.traineddata /opt/tessdata/eng.traineddata
RUN chmod -R 755 /opt/tessdata

COPY --from=build /workspace/build/libs/app*.jar /app/app.jar
COPY docker/start.sh /app/start.sh
RUN chmod +x /app/start.sh

# Optional diagnostics: enable with --build-arg DEBUG_DIAG=true
RUN if [ "$DEBUG_DIAG" = "true" ]; then \
      echo "tessdata md5:" && md5sum /opt/tessdata/eng.traineddata && \
      echo "PDFBox version:" && java -cp /app/app.jar org.apache.pdfbox.util.Version && \
      echo "Java settings:" && java -XshowSettings:properties -version 2>&1 | head -n 20 && \
      echo "Fonts count:" && (command -v fc-list >/dev/null && fc-list | wc -l || echo "fc-list missing") && \
      echo "Fonts sample:" && (command -v fc-list >/dev/null && fc-list | head -n 20 || echo "fc-list missing"); \
    fi

EXPOSE 8080
HEALTHCHECK --interval=1m --timeout=5s --retries=3 CMD [ -r /opt/tessdata/eng.traineddata ] || exit 1

ENTRYPOINT ["/app/start.sh"]
