-- Idempotency for app-owned consignment creation. Legacy tables remain unchanged.
ALTER TABLE app_solicitudes_consignacion
    ADD COLUMN idempotency_key varchar(100);

CREATE UNIQUE INDEX uq_app_consignacion_account_idempotency
    ON app_solicitudes_consignacion (cuenta_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
