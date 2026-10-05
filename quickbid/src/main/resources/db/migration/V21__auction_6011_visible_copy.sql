-- Ajuste final idempotente de copy visible app-owned.
-- No modifica tablas legacy ni contratos técnicos.

UPDATE app_subasta_ext
SET descripcion = 'Subasta de diseño y colección con lotes sucesivos, participación en vivo y cierre automático.',
    updated_at = CURRENT_TIMESTAMP
WHERE subasta_id = 6011;
