# DocuQuery Gateway

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.5-brightgreen)](https://spring.io/projects/spring-boot)
[![Spring Cloud](https://img.shields.io/badge/Spring%20Cloud-2025.1.1-blue)](https://spring.io/projects/spring-cloud-gateway)
[![Java](https://img.shields.io/badge/Java-25-orange)](https://openjdk.org/projects/jdk/25/)
[![Redis](https://img.shields.io/badge/Redis-Reactive-red)](https://redis.io/)

DocuQuery Gateway is a high-performance, reactive API Gateway built on **Spring Cloud Gateway (WebFlux)** and **Java 25**. It serves as the primary entry point for the DocuQuery ecosystem, providing centralized routing, path rewriting, and Redis-backed rate limiting.

## Key Features

- **Reactive Core**: Built using Project Reactor for non-blocking I/O.
- **Dynamic Routing**: Proxies requests to downstream DocuQuery services with flexible path rewriting.
- **Redis Rate Limiting**: Intelligent rate limiting per API Key and endpoint (e.g., higher limits for queries than uploads).
- **Header-based Auth**: Enforces API Key requirements (`X-API-Key`) for protected routes.
- **Observability**: Integrated health checks and gateway management via Spring Boot Actuator.
- **Container-Ready**: Optimized multi-stage Docker build using Eclipse Temurin 25.

---

## Tech Stack

- **Language**: Java 25
- **Framework**: Spring Boot 4.0.5 / Spring Cloud Gateway
- **Data Store**: Redis (Reactive)
- **Build Tool**: Maven
- **Infrastructure**: Docker

---

## Configuration

The application can be configured using environment variables. Default values are optimized for local development.

| Environment Variable | Default Value | Description |
| :--- | :--- | :--- |
| `SERVER_PORT` | `8080` | Gateway listening port. |
| `REDIS_HOST` | `redis` | Redis server hostname/IP. |
| `REDIS_PORT` | `6379` | Redis server port. |
| `DOCUQUERY_API_URL` | `http://localhost:8000` | Backend API service URL. |

---

## API Reference & Routing

The gateway handles the following routes and enforces rate limits via the `X-API-Key` header.

| Route | Upstream Path | Rate Limit (Capacity) | Refill Rate | Required Header |
| :--- | :--- | :--- | :--- | :--- |
| `POST /doc/upload` | `/upload` | 10 req/sec | 5 tokens/sec | `X-API-Key` |
| `POST /doc/ask` | `/ask` | 30 req/sec | 10 tokens/sec | `X-API-Key` |
| `GET /doc/upload/status/**` | `/upload/status/**` | Custom | N/A | None |

> [!IMPORTANT]
> Rate limiting is performed per `API Key` + `Path`. If the `X-API-Key` header is missing, the gateway returns `401 Unauthorized`.

---

## Getting Started

### Local Development

1. **Prerequisites**:
   - Java 25
   - Maven 3.9+
   - Redis running locally on `localhost:6379`

2. **Build and Run**:
   ```bash
   # Clone the repo
   git clone <repo-url>
   cd docuquery-gateway

   # Build
   ./mvnw clean install

   # Run
   java -jar target/gateway-0.0.1-SNAPSHOT.jar
   ```

### Docker Deployment

The project includes a multi-stage `Dockerfile` for easy containerization.

```bash
# Build the image
docker build -t docuquery-gateway .

# Run the container
docker run -p 8080:8080 -e REDIS_HOST=host.docker.internal docuquery-gateway
```

---

## Health & Monitoring

Standard Spring Boot Actuator endpoints are exposed for monitoring:

- **Health Check**: `GET http://localhost:8080/actuator/health`
- **Gateway Info**: `GET http://localhost:8080/actuator/gateway/routes`

---

## Usage Example

To query the DocuQuery system through the gateway:

```bash
curl -X POST http://localhost:8080/doc/ask \
     -H "X-API-Key: your_test_key" \
     -H "Content-Type: application/json" \
     -d '{"query": "What is the capital of France?"}'
```