# 01a — Backend Foundation

**Parent:** `01-infrastructure.md`
**Persona:** `@pharmacy-infra`

## Goal

Realign `pom.xml` to the real dependency set, replace `application.properties` with `application.yml`, lay out the package structure, and land the cross-cutting infrastructure every later unit assumes: `UuidConverter`, CORS, actuator, OpenAPI.

## Design

- `server.port: ${SERVER_PORT:8081}`
- `spring.jpa.hibernate.ddl-auto: validate` — never `update`
- `spring.jpa.open-in-view: false` — SCMS leaves this `true` by default, which silently holds a connection and a persistence context open for the whole request. Every unit in this spec is written assuming it is `false`, so lazy loading outside a transaction fails loudly rather than accidentally working.
- Flyway enabled with `baseline-on-migrate: false`
- CORS restricted to `http://localhost:5163` via `app.cors.allowed-origins`
- Actuator exposes only `health` and `info`
- springdoc path `/swagger-ui.html`

## Implementation

### `pom.xml`

Keep: `spring-boot-starter-webmvc`, `-data-jpa`, `-security`, `-actuator`, `org.postgresql:postgresql`, `lombok`, `springdoc-openapi-starter-webmvc-ui:3.1.0`, the four `*-test` starters.

Remove:
- `spring-boot-starter-batch` + `spring-boot-starter-batch-test` — no batch jobs in v1
- `spring-boot-starter-amqp` + `spring-boot-starter-amqp-test` — no broker in v1
- `spring-boot-starter-restdocs` + `spring-restdocs-mockmvc` + the whole `asciidoctor-maven-plugin` execution — REST Docs was scaffolding boilerplate and is unused
- `spring-boot-starter-actuator-test` → replaced by `spring-boot-starter-test` if not already implied

Add:
- `spring-boot-starter-validation` — **the critical addition.** The generated POM omits it, so `@Valid` silently does nothing. This is the exact bug in SCMS.
- `spring-boot-flyway`, `flyway-core`, `flyway-database-postgresql`  (all three: `flyway-database-postgresql` supplies the
  PostgreSQL support Flyway loads by reflection, and `spring-boot-flyway` is what actually
  auto-configures the migration runner — without it Boot 4 starts cleanly and silently
  migrates nothing)
- `spring-boot-starter-test` (already implied by the boot 4 test starters — confirm before adding a duplicate)
- `io.jsonwebtoken:jjwt-api` `0.12.x` + `jjwt-impl` + `jjwt-jackson` (runtime, `provided`-style scope as jjwt requires)

Add a `<dependencyManagement>` block pinning jjwt so `impl` and `jackson` versions cannot drift from `api`.

### `application.yml`

Delete `application.properties`. Create `application.yml` per `code-standards.md` §4 — every value a `${ENV:default}` placeholder, no hardcoded credential.

```yaml
server:
  port: ${SERVER_PORT:8081}

spring:
  application:
    name: pharmacy
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:3306/pharmacy}
    username: ${DB_USERNAME:pharmacy}
    password: ${DB_PASSWORD:pharmacy}
    driver-class-name: org.postgresql.Dric.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
    properties:
      hibernate:
        jdbc.time_zone: UTC
  flyway:
    enabled: true
    baseline-on-migrate: false
    locations: classpath:db/migration

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: when-authorized

springdoc:
  swagger-ui:
    path: /swagger-ui.html

app:
  jwt:
    secret: ${JWT_SECRET:change-me-in-every-real-deployment}
    access-token-minutes: 15
    refresh-token-days: 7
  cors:
    allowed-origins: ${CORS_ORIGINS:http://localhost:5163}
  upload:
    dir: ${UPLOAD_DIR:./uploads}
    max-size-bytes: 10485760
  hms:
    mode: ${HMS_MODE:mock}
    api-url: ${HMS_API_URL:http://localhost:8080}
```

`open-in-view: false` and `jdbc.time_zone: UTC` are both deliberate. Expiry dates are compared against `LocalDate.now()` in FEFO; a drifting session timezone would silently shift the FEFO boundary by a day.

### Package layout

```text
com.greedit.pharmacy
├── PharmacyApplication.java          @SpringBootApplication, @ConfigurationPropertiesScan
├── config/                           CorsConfig, OpenApiConfig, SecurityConfig (stub)
├── common/
│   ├── converter/   (empty — UuidConverter removed in Unit 02)
│   ├── dto/PageResponse.java
│   ├── dto/ApiError.java
│   ├── exception/                    the five typed exceptions
│   └── handler/GlobalExceptionHandler.java
├── entity/                           empty in this unit
├── repository/                       empty in this unit
├── service/                          empty in this unit
├── controller/                       PingController
└── client/                           HmsClient + MockHmsClient stub — see 10d
```

`client/` is created here so the seam exists from the start. `MDD.md` §4.3.

### ~~`UuidConverter`~~ — deleted in Unit 02

Originally specified here as `@Converter(autoApply = true)`, `UUID ↔ byte[16]`
big-endian, to store MySQL `BINARY(16)`. Decision 22 removed MySQL, so nothing
called it and it was deleted along with its 21 tests. Native PostgreSQL `uuid`
needs no converter and no `autoApply` — and a global `autoApply` would have been
the wrong shape anyway, since it silently converts any `String` UUID on the
persistence path.

**Do not reintroduce it.** If a future migration needs bytes, convert per field.

### `PharmacyApplication`

`@SpringBootApplication` + `@ConfigurationPropertiesScan`. Delete `templates/` and `static/` — empty server-rendering leftovers.

### `PharmacyApplicationTests`

The generated test asserts the context loads. That is worth keeping **only if the
context can actually load** — which needs PostgreSQL. Replace with a test that does
not require a database: a `@SpringBootTest` with `spring.autoconfigure.exclude` for
`DataSourceAutoConfiguration`, plus plain unit tests that need no infrastructure.
A test that fails only because the database is absent trains everyone to ignore
red.

## Dependencies

See the parent spec table. This sub-spec adds `spring-boot-starter-validation`, `spring-boot-flyway`, `flyway-core`, `flyway-database-postgresql`, jjwt.

## Verify when done

- [ ] `mvn -q clean verify` passes
- [ ] `mvn dependency:tree` shows no `spring-boot-starter-batch`, no `-amqp`, no asciidoctor
- [ ] `mvn dependency:tree | grep jjwt` shows `api`, `impl`, `jackson` all at the same version
- [ ] `spring-boot-starter-validation` is on the classpath
- [ ] Application starts on `8081`
- [ ] `/actuator/health` is `UP` and shows a `db` component
- [ ] `/v3/api-docs` and `/swagger-ui.html` respond
- [ ] `application.properties` is gone; only `application.yml` remains
- [ ] `templates/` and `static/` are gone
- [ ] ~~`UuidConverterTest` round-trips a UUID and asserts the 16-byte length
- [ ] `PharmacyApplicationTests` passes **without** a running database
- [ ] Grep for `password` across committed config returns only `${...}` placeholders
