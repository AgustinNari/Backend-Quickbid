ALTER TABLE app_subasta_ext
    ADD COLUMN IF NOT EXISTS monto_minimo integer,
    ADD COLUMN IF NOT EXISTS monto_maximo integer;