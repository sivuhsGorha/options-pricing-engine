# Stage 0: Frontend build (Vite writes to ../web, i.e. /fe/web)
FROM node:22-alpine AS frontend
WORKDIR /fe/web-react
COPY web-react/package.json web-react/package-lock.json ./
RUN npm ci
COPY web-react/ ./
RUN npm run build

# Stage 1: Build Stage
FROM maven:3.9-eclipse-temurin-25-alpine AS builder

WORKDIR /build

# Copy Maven descriptor and project files
COPY pom.xml .
COPY src ./src

# Compile all sources and run tests
# Tests and coverage gates run in CI before the image is built.
RUN mvn -B --no-transfer-progress -DskipTests -Djacoco.skip=true clean package

# Stage 2: Runtime Stage
FROM eclipse-temurin:25-jre-alpine

# Security: Create non-root group and user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# Copy artifact, web files, and required CSV state from builder
COPY --from=builder /build/target/options-pricing-engine-1.0.0-SNAPSHOT.jar /app/app.jar
COPY --from=builder /build/target/lib /app/lib
COPY --from=frontend /fe/web /app/web
COPY market_data.csv /app/market_data.csv

# Writable state directory (mmap state file, fill ledger); docker-compose mounts a volume here
RUN mkdir -p /app/data && chown -R appuser:appgroup /app

USER appuser

EXPOSE 8080 8081

HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/ || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "--add-modules", "jdk.incubator.vector", "-jar", "/app/app.jar"]
