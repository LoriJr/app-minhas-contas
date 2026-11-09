FROM node:22-alpine AS frontend
WORKDIR /app
COPY frontend/package*.json ./
RUN npm install --no-audit --no-fund
COPY frontend/ ./
RUN npm run build
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /app
COPY backend/ ./
COPY --from=frontend /app/dist/ src/main/resources/static/
RUN mvn -B verify
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=backend /app/target/fatura-*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
