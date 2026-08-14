# Spring Boot consumer smoke test

This sample depends on both idkit Spring Boot starters and selects one backend per test through
`idkit.backend`.

Run it from this directory:

```bash
../../gradlew test
```

The tests start PostgreSQL and Redis with Testcontainers and verify that a Java Spring Boot
application can inject `IdGenerator<Long>` for either backend. Docker is required.
