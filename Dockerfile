# ========================================================
# Dockerfile for Aram Office E-Governance Document System
# Optimized for Java 21 and Railway Deployment
# ========================================================

# Stage 1: Build Java classes
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Copy libraries and source files
COPY lib/ ./lib/
COPY src/ ./src/

# Compile all Java sources including MySQL JDBC driver
RUN mkdir -p out && javac -cp "lib/*" -d out src/*.java

# Stage 2: Runtime environment
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy compiled classes, libraries, frontend and initial data
COPY --from=builder /app/out ./out
COPY lib/ ./lib/
COPY web/ ./web/
COPY data/ ./data/

# Default port (Railway dynamically injects $PORT at runtime)
ENV PORT=8080

EXPOSE 8080

# Start Java WebServer with MySQL JDBC driver on classpath (Linux uses ':' separator)
CMD ["java", "-cp", "out:lib/*", "WebServer"]
