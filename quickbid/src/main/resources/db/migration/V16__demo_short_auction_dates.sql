-- Demo/presentacion: relaja solo la fecha minima legacy para permitir subastas cercanas.
-- No cambia el modelo funcional de la app; la regla normal queda en backend salvo modo demo.
ALTER TABLE subastas
    DROP CONSTRAINT IF EXISTS "chkFecha";

ALTER TABLE subastas
    ADD CONSTRAINT "chkFecha" CHECK (fecha >= CURRENT_DATE);
