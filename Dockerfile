FROM node:22-bookworm-slim AS frontend
WORKDIR /app/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY contracts /app/contracts
COPY frontend ./
RUN npm run build

FROM eclipse-temurin:21-jdk AS backend
WORKDIR /app/backend
COPY backend ./
COPY contracts /app/contracts
COPY --from=frontend /app/frontend/out /app/frontend/out
RUN chmod +x mvnw && ./mvnw -B -ntp -Pweb package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=backend /app/backend/target/adaptive-quote.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
