CREATE TABLE failed_stock_updates (id bigserial PRIMARY KEY, actor_id bigint REFERENCES app_users(id), reason text NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE alert_dispatches (alert_type varchar(60) NOT NULL, reference_id bigint NOT NULL, alert_date date NOT NULL, PRIMARY KEY(alert_type,reference_id,alert_date));
