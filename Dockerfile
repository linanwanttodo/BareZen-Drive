# ---- stage 1: web wasm dist ----
FROM gradle:9.7.1-jdk21 AS web
WORKDIR /src
# Node.js (Kotlin/JS toolchain) needs libatomic.so.1 which the gradle image lacks.
# Exact pins are not reproducible across the rolling gradle base image (build-time only).
# hadolint ignore=DL3008
RUN apt-get update && apt-get install -y --no-install-recommends libatomic1 && rm -rf /var/lib/apt/lists/*
COPY . .
RUN gradle :app:webApp:wasmJsBrowserDistribution --no-daemon

# ---- stage 2: server ----
FROM gradle:9.7.1-jdk21 AS server
WORKDIR /src
COPY . .
COPY --from=web /src/app/webApp/build/dist/wasmJs/productionExecutable /src/server/src/main/resources/web
RUN gradle :server:installDist --no-daemon

# ---- stage 3: runtime ----
FROM eclipse-temurin:21-jre-alpine
# ffmpeg powers server-side thumbnail fallback (video frames + exotic image formats).
# The alpine repo version moves with releases; pinning blocks builds.
# hadolint ignore=DL3018
RUN apk add --no-cache ffmpeg && addgroup -g 10001 -S app && adduser -u 10001 -S app -G app
WORKDIR /app
COPY --from=server /src/server/build/install/server ./
RUN chown -R 10001:10001 /app
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-Xms64m -Xmx256m"
EXPOSE 8080
# A fresh DB-backed /health response proves the process actually serves.
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD ["wget", "-qO-", "http://127.0.0.1:8080/health"]
ENTRYPOINT ["./bin/server"]
