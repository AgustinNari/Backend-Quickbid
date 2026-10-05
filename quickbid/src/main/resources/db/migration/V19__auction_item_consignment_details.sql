-- App-owned presentation details for consigned auction lots.
-- Legacy tables remain unchanged.

ALTER TABLE app_solicitudes_consignacion
    ADD COLUMN IF NOT EXISTS historia_extendida text;

UPDATE app_solicitudes_consignacion
SET historia_extendida = CASE id
        WHEN 16111 THEN 'Sillón de roble con líneas escandinavas, restaurado con terminación al aceite y tapizado renovado en género neutro.'
        WHEN 16112 THEN 'Conjunto conservado como servicio de mesa familiar; piezas revisadas por el equipo de catalogación antes de su publicación.'
        WHEN 16113 THEN 'Reloj mecánico de sobremesa con llave original y revisión de marcha realizada antes de ingresar a custodia.'
        ELSE historia_extendida
    END,
    historia = CASE id
        WHEN 16110 THEN 'Lámpara restaurada por taller especializado, con cableado revisado y terminación conservada.'
        WHEN 16111 THEN 'Pieza de mobiliario de procedencia particular, conservada en ambiente interior.'
        WHEN 16112 THEN 'Vajilla art déco completa para mesa de seis, proveniente de colección familiar.'
        WHEN 16113 THEN 'Reloj de mesa coleccionable con mecanismo original y procedencia particular documentada.'
        ELSE historia
    END,
    descripcion = CASE id
        WHEN 16007 THEN 'Mesa restaurada lista para liquidación.'
        WHEN 16110 THEN 'Lámpara italiana restaurada para subasta cercana.'
        WHEN 16111 THEN 'Sillón escandinavo de roble publicado como primer lote.'
        WHEN 16112 THEN 'Juego de vajilla art déco publicado como segundo lote.'
        WHEN 16113 THEN 'Reloj de mesa coleccionable publicado como tercer lote.'
        ELSE descripcion
    END,
    artista_disenador = CASE id
        WHEN 16110 THEN 'Taller de restauración QuickBid'
        WHEN 16111 THEN 'Diseñador escandinavo'
        WHEN 16112 THEN 'Casa art déco'
        WHEN 16113 THEN 'Relojero histórico'
        ELSE artista_disenador
    END,
    ubicacion_fisica = CASE id
        WHEN 16007 THEN 'Depósito de custodia QuickBid, Buenos Aires'
        WHEN 16110 THEN 'Depósito de custodia QuickBid - Sala principal, Buenos Aires'
        WHEN 16111 THEN 'Depósito de custodia QuickBid - Sala principal, Buenos Aires'
        WHEN 16112 THEN 'Depósito de custodia QuickBid - Sala principal, Buenos Aires'
        WHEN 16113 THEN 'Depósito de custodia QuickBid - Sala principal, Buenos Aires'
        ELSE ubicacion_fisica
    END,
    acuerdo_texto = CASE id
        WHEN 16007 THEN 'Acuerdo de consignación aceptado.'
        WHEN 16110 THEN 'Condiciones comerciales aceptadas para la publicación del bien.'
        WHEN 16111 THEN 'Condiciones comerciales aceptadas para la publicación del bien.'
        WHEN 16112 THEN 'Condiciones comerciales aceptadas para la publicación del bien.'
        WHEN 16113 THEN 'Condiciones comerciales aceptadas para la publicación del bien.'
        ELSE acuerdo_texto
    END,
    updated_at = CURRENT_TIMESTAMP
WHERE id IN (16007, 16110, 16111, 16112, 16113);

UPDATE app_subasta_ext
SET titulo = CASE subasta_id
        WHEN 6010 THEN 'Subasta de diseño e iluminación'
        WHEN 6011 THEN 'Subasta en vivo QuickBid'
        ELSE titulo
    END,
    descripcion = CASE subasta_id
        WHEN 6010 THEN 'Subasta cercana de diseño e iluminación con inscripción online.'
        WHEN 6011 THEN 'Subasta en vivo con lotes seleccionados, pujas alternadas y cierre final.'
        ELSE descripcion
    END,
    segmento = CASE subasta_id
        WHEN 6010 THEN 'diseno'
        WHEN 6011 THEN 'diseno'
        ELSE segmento
    END,
    updated_at = CURRENT_TIMESTAMP
WHERE subasta_id IN (6010, 6011);

UPDATE app_notificaciones
SET titulo = CASE id
        WHEN 18010 THEN 'Subasta lista'
        WHEN 18011 THEN 'Subasta lista'
        WHEN 18012 THEN 'Pólizas disponibles'
        ELSE titulo
    END,
    descripcion = CASE id
        WHEN 18010 THEN 'Ya estás inscripto en la subasta multi-lote.'
        WHEN 18011 THEN 'Ya estás inscripta en la subasta multi-lote.'
        WHEN 18012 THEN 'Los lotes tienen póliza y acuerdo descargable.'
        ELSE descripcion
    END
WHERE id IN (18010, 18011, 18012);
