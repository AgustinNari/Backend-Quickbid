-- Backfill de documentos seed de consignacion.
-- Rango reservado: app_archivos 4301..4307, app_documentos 17421..17427.
WITH pdf_source(bytes) AS (
    VALUES (decode('255044462D312E340A312030206F626A0A3C3C202F54797065202F436174616C6F67202F5061676573203220302052203E3E0A656E646F626A0A322030206F626A0A3C3C202F54797065202F5061676573202F4B696473205B33203020525D202F436F756E742031203E3E0A656E646F626A0A332030206F626A0A3C3C202F54797065202F50616765202F506172656E74203220302052202F4D65646961426F78205B30203020333030203134345D202F436F6E74656E7473203420302052202F5265736F7572636573203C3C202F466F6E74203C3C202F4631203520302052203E3E203E3E203E3E0A656E646F626A0A342030206F626A0A3C3C202F4C656E677468203837203E3E0A73747265616D0A4254202F4631203132205466203330203130302054642028446F63756D656E746F20517569636B4269642920546A2030202D32302054642028436F6E7369676E6163696F6E20646973706F6E69626C652920546A2045540A656E6473747265616D0A656E646F626A0A352030206F626A0A3C3C202F54797065202F466F6E74202F53756274797065202F5479706531202F42617365466F6E74202F48656C766574696361203E3E0A656E646F626A0A787265660A3020360A303030303030303030302036353533352066200A30303030303030303039203030303030206E200A30303030303030303538203030303030206E200A30303030303030313135203030303030206E200A30303030303030323431203030303030206E200A30303030303030333738203030303030206E200A747261696C6572203C3C202F53697A652036202F526F6F74203120302052203E3E0A7374617274787265660A3434380A2525454F460A', 'hex'))
),
known_files(id, filename_original, storage_path, checksum) AS (
    VALUES
        (4030::bigint, 'liquidacion_venta-16007.pdf', 'demo/documentos/liquidacion_venta-16007.pdf', 'seed-pdf-liquidacion-16007'),
        (4201::bigint, 'poliza_consignacion-16110.pdf', 'demo/documentos/poliza_consignacion-16110.pdf', 'seed-pdf-poliza-16110'),
        (4202::bigint, 'poliza_consignacion-16111.pdf', 'demo/documentos/poliza_consignacion-16111.pdf', 'seed-pdf-poliza-16111'),
        (4203::bigint, 'poliza_consignacion-16112.pdf', 'demo/documentos/poliza_consignacion-16112.pdf', 'seed-pdf-poliza-16112'),
        (4204::bigint, 'poliza_consignacion-16113.pdf', 'demo/documentos/poliza_consignacion-16113.pdf', 'seed-pdf-poliza-16113'),
        (4205::bigint, 'acuerdo_consignacion-16111.pdf', 'demo/documentos/acuerdo_consignacion-16111.pdf', 'seed-pdf-acuerdo-16111')
)
UPDATE app_archivos a
SET filename_original = k.filename_original,
    tipo_contexto = 'documento_consignacion',
    content_type = 'application/pdf',
    size_bytes = octet_length(p.bytes),
    storage_path = k.storage_path,
    checksum = k.checksum,
    content_bytes = p.bytes
FROM known_files k
CROSS JOIN pdf_source p
WHERE a.id = k.id;

WITH pdf_source(bytes) AS (
    VALUES (decode('255044462D312E340A312030206F626A0A3C3C202F54797065202F436174616C6F67202F5061676573203220302052203E3E0A656E646F626A0A322030206F626A0A3C3C202F54797065202F5061676573202F4B696473205B33203020525D202F436F756E742031203E3E0A656E646F626A0A332030206F626A0A3C3C202F54797065202F50616765202F506172656E74203220302052202F4D65646961426F78205B30203020333030203134345D202F436F6E74656E7473203420302052202F5265736F7572636573203C3C202F466F6E74203C3C202F4631203520302052203E3E203E3E203E3E0A656E646F626A0A342030206F626A0A3C3C202F4C656E677468203837203E3E0A73747265616D0A4254202F4631203132205466203330203130302054642028446F63756D656E746F20517569636B4269642920546A2030202D32302054642028436F6E7369676E6163696F6E20646973706F6E69626C652920546A2045540A656E6473747265616D0A656E646F626A0A352030206F626A0A3C3C202F54797065202F466F6E74202F53756274797065202F5479706531202F42617365466F6E74202F48656C766574696361203E3E0A656E646F626A0A787265660A3020360A303030303030303030302036353533352066200A30303030303030303039203030303030206E200A30303030303030303538203030303030206E200A30303030303030313135203030303030206E200A30303030303030323431203030303030206E200A30303030303030333738203030303030206E200A747261696C6572203C3C202F53697A652036202F526F6F74203120302052203E3E0A7374617274787265660A3434380A2525454F460A', 'hex'))
),
new_files(id, owner_cuenta_id, filename_original, storage_path, checksum) AS (
    VALUES
        (4301::bigint, 3004::bigint, 'acuerdo_consignacion-16004.pdf', 'demo/documentos/acuerdo_consignacion-16004.pdf', 'seed-pdf-acuerdo-16004'),
        (4302::bigint, 3004::bigint, 'acuerdo_consignacion-16007.pdf', 'demo/documentos/acuerdo_consignacion-16007.pdf', 'seed-pdf-acuerdo-16007'),
        (4303::bigint, 3004::bigint, 'poliza_consignacion-16007.pdf', 'demo/documentos/poliza_consignacion-16007.pdf', 'seed-pdf-poliza-16007'),
        (4305::bigint, 3004::bigint, 'acuerdo_consignacion-16110.pdf', 'demo/documentos/acuerdo_consignacion-16110.pdf', 'seed-pdf-acuerdo-16110'),
        (4306::bigint, 3004::bigint, 'acuerdo_consignacion-16112.pdf', 'demo/documentos/acuerdo_consignacion-16112.pdf', 'seed-pdf-acuerdo-16112'),
        (4307::bigint, 3004::bigint, 'acuerdo_consignacion-16113.pdf', 'demo/documentos/acuerdo_consignacion-16113.pdf', 'seed-pdf-acuerdo-16113')
)
INSERT INTO app_archivos (
    id, owner_cuenta_id, tipo_contexto, filename_original, content_type,
    size_bytes, storage_path, checksum, content_bytes
)
SELECT f.id, f.owner_cuenta_id, 'documento_consignacion', f.filename_original,
       'application/pdf', octet_length(p.bytes), f.storage_path, f.checksum, p.bytes
FROM new_files f
CROSS JOIN pdf_source p
ON CONFLICT (id) DO UPDATE SET
    owner_cuenta_id = EXCLUDED.owner_cuenta_id,
    tipo_contexto = EXCLUDED.tipo_contexto,
    filename_original = EXCLUDED.filename_original,
    content_type = EXCLUDED.content_type,
    size_bytes = EXCLUDED.size_bytes,
    storage_path = EXCLUDED.storage_path,
    checksum = EXCLUDED.checksum,
    content_bytes = EXCLUDED.content_bytes;

UPDATE app_documentos
SET estado = 'disponible'
WHERE referencia_tipo = 'consignacion'
  AND referencia_id IN (16007, 16110, 16111, 16112, 16113)
  AND tipo IN ('acuerdo_consignacion', 'poliza_consignacion', 'poliza_seguro', 'liquidacion_venta');

WITH expected_docs(id, tipo, referencia_id, archivo_id) AS (
    VALUES
        (17421::bigint, 'acuerdo_consignacion', 16004::bigint, 4301::bigint),
        (17422::bigint, 'acuerdo_consignacion', 16007::bigint, 4302::bigint),
        (17423::bigint, 'poliza_consignacion', 16007::bigint, 4303::bigint),
        (17425::bigint, 'acuerdo_consignacion', 16110::bigint, 4305::bigint),
        (17426::bigint, 'acuerdo_consignacion', 16112::bigint, 4306::bigint),
        (17427::bigint, 'acuerdo_consignacion', 16113::bigint, 4307::bigint)
)
INSERT INTO app_documentos (id, tipo, referencia_tipo, referencia_id, archivo_id, estado)
SELECT e.id, e.tipo, 'consignacion', e.referencia_id, e.archivo_id, 'disponible'
FROM expected_docs e
WHERE NOT EXISTS (
    SELECT 1
    FROM app_documentos d
    WHERE d.referencia_tipo = 'consignacion'
      AND d.referencia_id = e.referencia_id
      AND d.tipo = e.tipo
);

INSERT INTO seguros ("nroPoliza", compania, "polizaCombinada", importe) VALUES
    ('POL-QB-8005', 'QuickBid Seguros', 'si', 2000.00),
    ('POL-QB-8010', 'QuickBid Seguros', 'si', 2400.00),
    ('POL-QB-8011', 'QuickBid Seguros', 'si', 3000.00),
    ('POL-QB-8012', 'QuickBid Seguros', 'si', 3600.00),
    ('POL-QB-8013', 'QuickBid Seguros', 'si', 4100.00)
ON CONFLICT ("nroPoliza") DO UPDATE SET
    compania = EXCLUDED.compania,
    "polizaCombinada" = EXCLUDED."polizaCombinada",
    importe = EXCLUDED.importe;

UPDATE productos
SET seguro = CASE identificador
    WHEN 8005 THEN 'POL-QB-8005'
    WHEN 8010 THEN 'POL-QB-8010'
    WHEN 8011 THEN 'POL-QB-8011'
    WHEN 8012 THEN 'POL-QB-8012'
    WHEN 8013 THEN 'POL-QB-8013'
    ELSE seguro
END
WHERE identificador IN (8005, 8010, 8011, 8012, 8013);

UPDATE seguros
SET compania = 'QuickBid Seguros'
WHERE compania = 'QuickBid Seguros Demo';

UPDATE app_solicitudes_consignacion
SET acuerdo_texto = CASE id
    WHEN 16004 THEN 'Condiciones comerciales listas para revisar y aceptar.'
    WHEN 16005 THEN 'Acuerdo de consignacion aceptado.'
    WHEN 16006 THEN 'Condiciones comerciales rechazadas por el consignador.'
    WHEN 16007 THEN 'Acuerdo de consignacion aceptado.'
    WHEN 16008 THEN 'Condiciones comerciales aceptadas para la publicacion del bien.'
    WHEN 16110 THEN 'Condiciones comerciales aceptadas para la publicacion del bien.'
    WHEN 16111 THEN 'Condiciones comerciales aceptadas para la publicacion del bien.'
    WHEN 16112 THEN 'Condiciones comerciales aceptadas para la publicacion del bien.'
    WHEN 16113 THEN 'Condiciones comerciales aceptadas para la publicacion del bien.'
    ELSE acuerdo_texto
END,
artista_disenador = CASE id
    WHEN 16002 THEN 'Artista consignado'
    WHEN 16008 THEN 'Escultor consignado'
    WHEN 16110 THEN 'Taller de restauracion'
    WHEN 16111 THEN 'Disenador escandinavo'
    WHEN 16112 THEN 'Casa art deco'
    WHEN 16113 THEN 'Relojero historico'
    ELSE artista_disenador
END,
descripcion = CASE id
    WHEN 16110 THEN 'Producto consignado para la subasta cercana.'
    ELSE descripcion
END,
updated_at = CURRENT_TIMESTAMP
WHERE id IN (16002, 16004, 16005, 16006, 16007, 16008, 16110, 16111, 16112, 16113);

UPDATE app_liquidaciones_consignacion
SET cuenta_destino = 'Cuenta bancaria registrada del consignador'
WHERE solicitud_id = 16007
  AND cuenta_destino ILIKE '%demo%';

UPDATE app_notificaciones
SET titulo = CASE id
        WHEN 18012 THEN 'Polizas disponibles'
        ELSE titulo
    END,
    descripcion = CASE id
        WHEN 18005 THEN 'La liquidacion ya esta disponible.'
        WHEN 18012 THEN 'Los lotes tienen poliza y acuerdo descargable.'
        ELSE descripcion
    END
WHERE id IN (18005, 18012);

SELECT setval(pg_get_serial_sequence('app_archivos', 'id'), (SELECT max(id) FROM app_archivos), true);
SELECT setval(pg_get_serial_sequence('app_documentos', 'id'), (SELECT max(id) FROM app_documentos), true);
