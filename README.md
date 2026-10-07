# QuickBid — Backend

Backend service for QuickBid, a mobile auction application developed as a team project.

It provides a REST API for registration and authentication, auction catalogs, real-time bidding, purchases, payments, consignments, user profiles, and notifications. The repository also includes demo data and tools for testing and local operation.

The mobile application is available in [Frontend-Quickbid](https://github.com/AgustinNari/Frontend-Quickbid).

## Tech Stack

- Java 17
- Spring Boot 4
- Maven
- Spring Security
- JWT authentication and refresh-token rotation
- JPA
- PostgreSQL
- Flyway
- WebSocket / STOMP
- Docker
- Spring Boot Actuator

The executable project is located in `quickbid/`.

## Architecture

The backend is organized around REST controllers, business services, repositories, entities, and adapters for email and file storage.

Application-specific tables extend the legacy database model through versioned Flyway migrations.

Authentication uses JWT access tokens and rotating refresh tokens. Auction bids and live auction events are delivered through WebSocket/STOMP.

Administrative endpoints are provided as auxiliary operational and testing tools; the project does not include a dedicated administration UI.

## Local Setup

Requirements:

- JDK 17
- PostgreSQL
- Docker, optionally

Create an empty PostgreSQL database named `quickbid` and configure the required environment variables.

PowerShell example:

```powershell
cd quickbid

$env:DB_URL = 'jdbc:postgresql://localhost:5432/quickbid'
$env:DB_USERNAME = 'postgres'
$env:DB_PASSWORD = '<local-password>'
$env:APP_JWT_SECRET = '<random-secret-with-at-least-32-characters>'

.\mvnw.cmd spring-boot:run
```

On Linux/macOS, export the same variables and run:

```bash
./mvnw spring-boot:run
```

Flyway applies the database migrations on startup and JPA validates the resulting schema.

The default HTTP port is `8080`.

Health endpoint:

```text
/actuator/health
```

## Configuration

| Variable | Purpose |
| --- | --- |
| `DB_URL` | PostgreSQL JDBC URL |
| `DB_USERNAME` | PostgreSQL user |
| `DB_PASSWORD` | PostgreSQL password |
| `APP_JWT_SECRET` | JWT signing secret |
| `PORT` | HTTP port, default `8080` |
| `APP_FILES_STORAGE_PATH` | Local file-storage directory |
| `APP_MAIL_ENABLED` | Enables email delivery |
| `APP_MAIL_PROVIDER` | Email provider: `smtp`, `resend`, or `brevo` |
| `APP_MAIL_FROM` | Sender address |
| `APP_MAIL_NOTIFICATIONS_ENABLED` | Enables business-event emails |
| `APP_FRONTEND_BASE_URL` | Base URL used for authentication links |
| `APP_PUBLIC_BASE_URL` | Public backend URL |
| `APP_ADMIN_ENABLED` | Enables auxiliary administration endpoints |
| `APP_ADMIN_INTERNAL_KEY` | Internal key for administrative operations |

Provider-specific variables are documented in:

```text
quickbid/src/main/resources/application.properties
```

Real credentials should never be committed to the repository.

## Testing

From `quickbid/`:

```bash
./mvnw clean verify
```

On Windows:

```powershell
.\mvnw.cmd clean verify
```

Tests run with the `test` profile using an in-memory H2 database and dedicated test fixtures.

Additional HTTP examples and validation utilities are available under:

```text
quickbid/docs/
```

Some examples modify data and should therefore be executed against a test database.

## Docker

Build the image from `quickbid/`:

```bash
docker build -t quickbid-backend .
```

Run it with:

```bash
docker run --rm -p 8080:8080 --env-file <local-env-file> quickbid-backend
```

PostgreSQL must be reachable from the container. File uploads can be persisted by mounting a volume at the configured storage path.

The Docker image build skips tests, so the test suite should be executed beforehand.

## Demo Scope

The database migrations include fictional demo accounts and data.

`APP_DEMO_*` options control demonstration-related behavior and do not represent a commercial payment integration.

The project also includes a configurable simulation of external payment-adjudication failures.

When email delivery is disabled, registration and password-recovery emails are simulated.

The STOMP broker, presence information, and rate limiting keep state in memory per application instance, while uploaded files use local storage. These constraints should be considered when reproducing the demo or running multiple instances.
