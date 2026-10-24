# Deployment Guide

> **CCE Intelligence Service** — Production deployment reference  
> **Version**: 1.0.0 | **Port**: 8085 | **Base Image**: eclipse-temurin:21-jre-alpine

---

## 1. Prerequisites

| Dependency | Version | Notes |
|-----------|---------|-------|
| PostgreSQL | 16+ | Shared `ccedb` database (port 5433) |
| Apache Kafka | 3.7+ | KRaft mode, 25 partitions on `cce.intelligence.triggers` |
| CCE Compliance Service | 1.0.0+ | Must have run Flyway to create `protocol_definition` table |
| JDK (build only) | 21 LTS | Not needed at runtime (JRE in container) |

---

## 2. Build

### 2.1 JAR

```bash
./gradlew build -x test --no-daemon
# Output: build/libs/cce-intelligence-service-1.0.0-SNAPSHOT.jar
```

### 2.2 Docker Image

```bash
docker build -t cce-intelligence-service:1.0.0 .
```

### 2.3 Verify Image

```bash
docker run --rm cce-intelligence-service:1.0.0 java -version
# Expected: openjdk 21.x.x
```

---

## 3. Configuration

All configuration is via environment variables. No config files need to be mounted.

### 3.1 Required Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `DB_HOST` | PostgreSQL hostname | `cce-postgres.internal` |
| `DB_PORT` | PostgreSQL port | `5433` |
| `DB_NAME` | Database name | `ccedb` |
| `DB_USERNAME` | Database user | `cce_user` |
| `DB_PASSWORD` | Database password | *(secret)* |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka broker addresses | `kafka-1:9092,kafka-2:9092` |

### 3.2 Optional Tuning

| Variable | Default | Description |
|----------|---------|-------------|
| `SERVER_PORT` | `8085` | Application HTTP port |
| `WEBHOOK_CONNECT_TIMEOUT_MS` | `5000` | Webhook connection timeout (ms) |
| `WEBHOOK_READ_TIMEOUT_MS` | `10000` | Webhook read timeout (ms) |
| `WEBHOOK_RETRY_ATTEMPTS` | `3` | Max webhook retry attempts |
| `WEBHOOK_RETRY_INTERVAL_MS` | `2000` | Delay between retries (ms) |
| `DB_POOL_SIZE` | `10` | HikariCP max pool size |

### 3.3 JVM Tuning

Set via `JAVA_OPTS` environment variable (defaults in Dockerfile):

```
-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError
```

Override for production with more memory:

```bash
JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=80.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Xlog:gc*:file=/tmp/gc.log"
```

---

## 4. Deployment

### 4.1 Docker Run

```bash
docker run -d \
  --name cce-intelligence-service \
  --restart unless-stopped \
  -p 8085:8085 \
  -e DB_HOST=cce-postgres.internal \
  -e DB_PORT=5433 \
  -e DB_NAME=ccedb \
  -e DB_USERNAME=cce_user \
  -e DB_PASSWORD=<secret> \
  -e KAFKA_BOOTSTRAP_SERVERS=kafka-1:9092,kafka-2:9092 \
  cce-intelligence-service:1.0.0
```

### 4.2 Kubernetes

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: cce-intelligence-service
  labels:
    app: cce-intelligence-service
spec:
  replicas: 2
  selector:
    matchLabels:
      app: cce-intelligence-service
  template:
    metadata:
      labels:
        app: cce-intelligence-service
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8085"
        prometheus.io/path: "/actuator/prometheus"
    spec:
      containers:
        - name: cce-intelligence-service
          image: cce-intelligence-service:1.0.0
          ports:
            - containerPort: 8085
              name: http
          env:
            - name: DB_HOST
              value: "cce-postgres.internal"
            - name: DB_PORT
              value: "5433"
            - name: DB_NAME
              value: "ccedb"
            - name: DB_USERNAME
              valueFrom:
                secretKeyRef:
                  name: cce-db-credentials
                  key: username
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: cce-db-credentials
                  key: password
            - name: KAFKA_BOOTSTRAP_SERVERS
              value: "kafka-headless:9092"
          resources:
            requests:
              cpu: 250m
              memory: 512Mi
            limits:
              cpu: "1"
              memory: 1Gi
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8085
            initialDelaySeconds: 30
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8085
            initialDelaySeconds: 60
            periodSeconds: 30
          startupProbe:
            httpGet:
              path: /actuator/health
              port: 8085
            initialDelaySeconds: 10
            periodSeconds: 5
            failureThreshold: 20
---
apiVersion: v1
kind: Service
metadata:
  name: cce-intelligence-service
spec:
  selector:
    app: cce-intelligence-service
  ports:
    - port: 8085
      targetPort: 8085
      name: http
  type: ClusterIP
```

---

## 5. Database Migrations

Flyway runs automatically on startup. No manual migration steps required.

**Startup order constraint:** No cross-service FK dependencies. The Intelligence Service is fully self-contained — the fat event design means no Compliance Service tables are referenced.

To verify migration status:
```bash
# Connect to PostgreSQL and check flyway history
psql -h $DB_HOST -p $DB_PORT -U $DB_USERNAME -d $DB_NAME \
  -c "SELECT * FROM flyway_schema_history ORDER BY installed_rank;"
```

---

## 6. Health Checks

| Endpoint | Purpose | Expected Response |
|----------|---------|-------------------|
| `GET /actuator/health` | Overall health | `{"status":"UP"}` |
| `GET /actuator/health/readiness` | Ready to accept traffic | `{"status":"UP"}` |
| `GET /actuator/health/liveness` | Process alive | `{"status":"UP"}` |

Health includes component checks for: `db` (PostgreSQL), `kafka` (broker connectivity), `diskSpace`.

---

## 7. Monitoring

### 7.1 Prometheus Metrics

Scrape endpoint: `GET /actuator/prometheus`

Key application metrics:

| Metric | Type | Description |
|--------|------|-------------|
| `cce_intelligence_triggers_received_total` | Counter | Trigger events consumed |
| `cce_intelligence_deliveries_dispatched_total` | Counter | Webhook deliveries attempted |
| `cce_intelligence_deliveries_delivered_total` | Counter | Successful deliveries |
| `cce_intelligence_deliveries_failed_total` | Counter | Failed deliveries |
| `cce_intelligence_webhook_duration_seconds` | Timer | Webhook response latency |
| `cce_intelligence_destinations_active` | Gauge | Active destination-adaptor mappings |
| `cce_intelligence_consumer_errors_total` | Counter | Consumer processing errors |

### 7.2 Alerting Recommendations

| Alert | Condition | Severity |
|-------|-----------|----------|
| High failure rate | `deliveries_failed / deliveries_dispatched > 0.1` over 5min | Critical |
| Consumer lag | Kafka consumer group lag > 1000 | Warning |
| Webhook latency | P99 `webhook_duration > 10s` | Warning |
| Service down | Health check failing for 2min | Critical |

---

## 8. Scaling

| Dimension | Strategy | Notes |
|-----------|----------|-------|
| Horizontal | Add replicas | Kafka consumer group auto-rebalances (25 partitions) |
| Vertical | Increase memory | Adjust `MaxRAMPercentage` or pod resource limits |
| DB connections | `DB_POOL_SIZE` per instance | Total connections = replicas × pool size |
| Kafka throughput | Increase `concurrency` (default: 3) | Listener threads per instance |

**Recommended production sizing:**
- 2–3 replicas for HA
- 1 CPU / 1Gi memory per replica
- 10 DB connections per replica (30 total for 3 replicas)

---

## 9. Rollback

The service uses Flyway with `baseline-on-migrate=true`. Rollback strategy:

1. **Application rollback:** Deploy the previous container image version. Flyway will validate (not re-run) existing migrations.
2. **Schema rollback:** Only needed if a migration was destructive. Write a compensating `V<next>__rollback_*.sql` migration.
3. **Zero-downtime deployments:** Use rolling update strategy (Kubernetes default). Old and new versions can coexist — schema changes are additive only.

---

## 10. Security Checklist

- [ ] `DB_PASSWORD` stored in secrets manager (not in env files or source control)
- [ ] Receiver Adaptor `config.authValue` encrypted at rest (pgcrypto or app-level)
- [ ] Container runs as non-root user (`cce`, UID 1001)
- [ ] Network policy restricts egress to known webhook endpoints
- [ ] Actuator endpoints restricted to internal network (via Gateway or network policy)
- [ ] Kafka connection uses SASL/SSL in production
- [ ] Image scanned for CVEs before deployment
