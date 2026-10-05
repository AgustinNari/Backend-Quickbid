-- Ajuste final de textos seed visibles en tablas app-owned.
-- No modifica tablas legacy ni contratos técnicos.

UPDATE app_medios_pago
SET alias_visible = CASE id
    WHEN 5002 THEN 'Cuenta USD'
    WHEN 5011 THEN 'Tarjeta ARS vencida'
    WHEN 5012 THEN 'Tarjeta ARS pendiente'
    WHEN 5013 THEN 'Tarjeta USD rechazada'
    ELSE alias_visible
END
WHERE id IN (5002, 5011, 5012, 5013);

UPDATE app_subasta_ext
SET titulo = CASE subasta_id
        WHEN 6010 THEN 'Diseño y colección con inscripción cercana'
        WHEN 6011 THEN 'Subasta en vivo QuickBid'
        ELSE titulo
    END,
    descripcion = CASE subasta_id
        WHEN 6002 THEN 'Subasta futura en dólares con inscripción previa.'
        WHEN 6003 THEN 'Subasta cerrada con compras registradas y pagos asociados.'
        WHEN 6004 THEN 'Subasta oro con incrementos flexibles.'
        WHEN 6010 THEN 'Subasta cercana con inscripción aprobada antes del vivo.'
        WHEN 6011 THEN 'Escenario multi-lote para compradores habilitados, pujas alternadas y cierre final.'
        ELSE descripcion
    END,
    updated_at = CURRENT_TIMESTAMP
WHERE subasta_id IN (6002, 6003, 6004, 6010, 6011);

UPDATE app_solicitudes_consignacion
SET acuerdo_texto = CASE id
    WHEN 16004 THEN 'Acuerdo pendiente de aceptación.'
    WHEN 16005 THEN 'Acuerdo de consignación aceptado.'
    WHEN 16006 THEN 'Condiciones comerciales rechazadas por el consignador.'
    WHEN 16007 THEN 'Acuerdo de consignación aceptado.'
    WHEN 16008 THEN 'Acuerdo aceptado, aún no publicado.'
    WHEN 16110 THEN 'Condiciones comerciales aceptadas para la publicación del bien.'
    WHEN 16111 THEN 'Condiciones comerciales aceptadas para la publicación del bien.'
    WHEN 16112 THEN 'Condiciones comerciales aceptadas para la publicación del bien.'
    WHEN 16113 THEN 'Condiciones comerciales aceptadas para la publicación del bien.'
    ELSE acuerdo_texto
END
WHERE id IN (16004, 16005, 16006, 16007, 16008, 16110, 16111, 16112, 16113);

UPDATE app_notificaciones
SET titulo = CASE id
        WHEN 18010 THEN 'Subasta lista'
        WHEN 18011 THEN 'Subasta lista'
        WHEN 18012 THEN 'Pólizas disponibles'
        ELSE titulo
    END,
    descripcion = CASE id
        WHEN 18002 THEN 'Tu puja quedó como mejor oferta actual.'
        WHEN 18005 THEN 'La liquidación ya está disponible.'
        WHEN 18010 THEN 'Ya estás inscripto en la subasta multi-lote.'
        WHEN 18011 THEN 'Ya estás inscripta en la subasta multi-lote.'
        WHEN 18012 THEN 'Los lotes tienen póliza y acuerdo descargable.'
        ELSE descripcion
    END
WHERE id IN (18002, 18005, 18010, 18011, 18012);
