ALTER TABLE stock_commands ADD COLUMN request_signature varchar(64) NOT NULL DEFAULT '';

CREATE OR REPLACE FUNCTION reject_stock_movement_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'stock_movements is append-only';
END;
$$;

CREATE TRIGGER stock_movement_no_update BEFORE UPDATE OR DELETE ON stock_movements
FOR EACH ROW EXECUTE FUNCTION reject_stock_movement_mutation();
