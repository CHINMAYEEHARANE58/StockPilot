# Deployment guide

## Prerequisites

- Java 21 for noncontainer deployments; Docker Engine with Compose for the bundled deployment.
- PostgreSQL 16 or later, a Kafka broker, and SMTP service.
- TLS termination at the ingress. The bundled Compose file is intended for local use; its database and Kafka listeners are plain TCP.

## Configuration

Set `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`, `SMTP_HOST`, `SMTP_PORT`, and `NOTIFICATIONS_FROM`. For authenticated SMTP, also set `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_AUTH=true`, and `SMTP_STARTTLS=true`. Set `SEED_DATA=false` in production. Configure a first owner separately with a BCrypt password hash in a controlled provisioning process. Use a secret manager for credentials and keep `.env` outside source control.

`RESERVATION_MINUTES` defaults to 30. In a multireplica deployment, each replica may run the expiration job; row locks and status checks make releases safe. The outbox publisher uses `SKIP LOCKED` so replicas can share work. Kafka messages are at least once; consumers use `consumed_events` to ignore duplicate IDs.

SMTP does not provide an idempotency key. If a consumer crashes after sending mail but before committing its `consumed_events` row, the recipient may receive a duplicate alert. The database state and stock commands remain idempotent.

## Release sequence

1. Back up PostgreSQL and verify a restore procedure.
2. Run Flyway migrations by starting one backend replica. Wait for health to become ready before rolling out other replicas.
3. Deploy the Angular static files behind a reverse proxy that forwards `/api` to the backend.
4. Verify `/actuator/health`, a login, a stock balance read, and the outbox backlog (`SELECT count(*) FROM outbox_events WHERE published_at IS NULL`).
5. Monitor consumer failures, reservation expiration, and nonempty reconciliation results.

For local evaluation, use `docker compose up --build -d` after setting the two passwords in `.env`; the initial login is `owner@stockpilot.local`.

## Recovery

Restore a PostgreSQL backup before replaying application traffic. The stock ledger and snapshots must be restored together. If Kafka was unavailable, unpublished outbox records remain in PostgreSQL and publish when connectivity returns. Do not manually modify `stock_balances`; use an adjustment with a reason so the ledger stays consistent. Run `/api/stock/reconciliation` after restoration and resolve any difference before accepting new orders.

## Known scope limits

Shipment tracking is stored as a tracking number and status; no carrier API is included. Low-stock emails are sent to active owners through the configured SMTP server. Valuation uses current purchase cost. The current UI formats monetary values as USD and assumes a single business currency; multi-currency accounting is outside this release. Warehouse utilization reports occupied SKUs and units because the schema has no physical capacity model.
