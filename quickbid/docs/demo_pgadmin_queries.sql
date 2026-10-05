-- QuickBid demo presentacion 6011 - consultas para pgAdmin
-- Ejecutar contra la base desplegada despues de aplicar Flyway V1..V17.

-- Migraciones aplicadas
SELECT installed_rank, version, description, success, installed_on
FROM flyway_schema_history
ORDER BY installed_rank;

-- Usuarios principales de demo
SELECT id, email, estado, puntos, categoria_calculada
FROM app_cuentas
WHERE email IN (
  'aprobado@quickbid.demo',
  'comprador2@quickbid.demo',
  'categoria@quickbid.demo',
  'multa@quickbid.demo',
  'bloqueado@quickbid.demo',
  'consignador@quickbid.demo',
  'oro@quickbid.demo'
)
ORDER BY id;

-- Medios de pago del comprador 2 y negativos
-- Esperado V17: 5010 verificado, 5011 vencido, 5012 pendiente, 5013 rechazado, 5014 limite bajo/categoria comun.
SELECT id, cuenta_id, tipo, moneda, estado, alias_visible, limite_monto, consumo_actual, verificado_hasta
FROM app_medios_pago
WHERE id IN (5010, 5011, 5012, 5013, 5014)
ORDER BY id;

-- Estado de la subasta multilote
SELECT s.identificador, s.fecha, s.hora, s.estado, s.categoria,
       e.titulo, e.moneda, e.segmento, e.estado_operativo
FROM subastas s
JOIN app_subasta_ext e ON e.subasta_id = s.identificador
WHERE s.identificador = 6011;

-- Estado vivo y timers
SELECT subasta_id, item_catalogo_activo_id, version, usuarios_conectados,
       lote_iniciado_at, retencion_hasta, lote_finaliza_estimado_at,
       proximo_lote_programado_at, subasta_finaliza_programado_at
FROM app_subasta_estado_vivo
WHERE subasta_id = 6011;

-- Snapshot tecnico del modo "esperando primera puja": item activo, sin retencion y sin deadline.
SELECT CASE
         WHEN item_catalogo_activo_id = 9011
          AND retencion_hasta IS NULL
          AND lote_finaliza_estimado_at IS NULL
         THEN 'esperando_primera_puja'
         WHEN item_catalogo_activo_id IS NOT NULL
          AND lote_finaliza_estimado_at IS NOT NULL
         THEN 'timer_activo'
         ELSE 'sin_lote_activo'
       END AS estado_timer_demo
FROM app_subasta_estado_vivo
WHERE subasta_id = 6011;

-- Catalogo, productos, polizas y consignaciones
SELECT i.identificador AS item_id,
       i.producto,
       i."precioBase",
       i.comision,
       i.subastado,
       p."descripcionCatalogo",
       p.duenio,
       p.seguro,
       sc.id AS consignacion_id,
       sc.estado AS consignacion_estado
FROM "itemsCatalogo" i
JOIN catalogos c ON c.identificador = i.catalogo
JOIN productos p ON p.identificador = i.producto
LEFT JOIN app_solicitudes_consignacion sc ON sc.item_catalogo_id = i.identificador
WHERE c.subasta = 6011
ORDER BY i.identificador;

-- Pujas y reservas de la demo
SELECT p.id, p.item_catalogo_id, p.cuenta_id, c.email, p.medio_pago_id,
       p.monto, p.estado, p.secuencia, p.version_estado,
       r.estado AS reserva_estado, r.monto AS reserva_monto
FROM app_pujas_live p
JOIN app_cuentas c ON c.id = p.cuenta_id
LEFT JOIN app_reservas_medio_pago r ON r.puja_id = p.id
WHERE p.subasta_id = 6011
ORDER BY p.item_catalogo_id, p.secuencia;

-- Compras generadas por cierre de lotes
SELECT co.id, co.item_catalogo_id, co.producto_id, co.cuenta_comprador_id,
       c.email AS comprador, co.puja_id, co.medio_pago_id,
       co.comprador_empresa, co.monto_adjudicacion, co.moneda, co.estado,
       co.comision_comprador, co.comision_vendedor
FROM app_compras co
LEFT JOIN app_cuentas c ON c.id = co.cuenta_comprador_id
WHERE co.subasta_id = 6011
ORDER BY co.item_catalogo_id;

-- Documentos de consignacion con bytes embebidos
SELECT d.id AS documento_id, d.tipo, d.referencia_tipo, d.referencia_id,
       a.id AS archivo_id, a.filename_original, a.content_type,
       a.size_bytes, octet_length(a.content_bytes) AS bytes_embebidos
FROM app_documentos d
JOIN app_archivos a ON a.id = d.archivo_id
WHERE d.referencia_tipo = 'consignacion'
  AND d.referencia_id IN (16111, 16112, 16113)
ORDER BY d.referencia_id, d.id;

-- Documentos de consignacion esperados para acuerdos, polizas y liquidacion seed.
SELECT s.id AS consignacion_id, s.estado AS consignacion_estado,
       d.tipo, d.estado AS documento_estado,
       a.id AS archivo_id, a.filename_original, a.content_type,
       a.size_bytes, octet_length(a.content_bytes) AS content_bytes,
       CASE
           WHEN d.estado = 'disponible'
            AND a.content_type = 'application/pdf'
            AND a.content_bytes IS NOT NULL
            AND a.size_bytes = octet_length(a.content_bytes)
           THEN true ELSE false
       END AS download_available_esperado
FROM app_solicitudes_consignacion s
LEFT JOIN app_documentos d
       ON d.referencia_tipo = 'consignacion'
      AND d.referencia_id = s.id
      AND d.tipo IN ('acuerdo_consignacion', 'poliza_consignacion', 'poliza_seguro', 'liquidacion_venta')
LEFT JOIN app_archivos a ON a.id = d.archivo_id
WHERE s.id IN (16004, 16007, 16110, 16111, 16112, 16113)
ORDER BY s.id, d.tipo, d.id;

-- Documentos faltantes por tipo esperado.
WITH expected(consignacion_id, tipo) AS (
    VALUES
        (16004, 'acuerdo_consignacion'),
        (16007, 'acuerdo_consignacion'),
        (16007, 'poliza_consignacion'),
        (16007, 'liquidacion_venta'),
        (16110, 'acuerdo_consignacion'),
        (16110, 'poliza'),
        (16111, 'acuerdo_consignacion'),
        (16111, 'poliza'),
        (16112, 'acuerdo_consignacion'),
        (16112, 'poliza'),
        (16113, 'acuerdo_consignacion'),
        (16113, 'poliza')
)
SELECT e.consignacion_id, e.tipo AS esperado
FROM expected e
WHERE NOT EXISTS (
    SELECT 1
    FROM app_documentos d
    JOIN app_archivos a ON a.id = d.archivo_id
    WHERE d.referencia_tipo = 'consignacion'
      AND d.referencia_id = e.consignacion_id
      AND (
          d.tipo = e.tipo
          OR (e.tipo = 'poliza' AND d.tipo IN ('poliza_consignacion', 'poliza_seguro'))
      )
      AND d.estado = 'disponible'
      AND a.content_bytes IS NOT NULL
      AND a.size_bytes = octet_length(a.content_bytes)
);

-- Validaciones rapidas de consistencia esperada
SELECT 'subasta_6011_existe' AS check_name, COUNT(*) AS total
FROM subastas WHERE identificador = 6011
UNION ALL
SELECT 'tres_lotes_6011', COUNT(*)
FROM "itemsCatalogo" i JOIN catalogos c ON c.identificador = i.catalogo
WHERE c.subasta = 6011
UNION ALL
SELECT 'comprador2_existe', COUNT(*)
FROM app_cuentas WHERE email = 'comprador2@quickbid.demo'
UNION ALL
SELECT 'documentos_con_bytes', COUNT(*)
FROM app_documentos d JOIN app_archivos a ON a.id = d.archivo_id
WHERE d.referencia_tipo = 'consignacion'
  AND d.referencia_id IN (16111, 16112, 16113)
  AND a.content_bytes IS NOT NULL
  AND octet_length(a.content_bytes) > 0;
-- Demo assets de imagenes cargados por DemoAssetSeedService

-- Conteo de fotos legacy por producto demo. Debe dar 6 en cada producto si las seis imagenes existen.
SELECT producto, COUNT(*) AS cantidad_fotos
FROM fotos
WHERE producto IN (8010, 8011, 8012, 8013)
  AND identificador BETWEEN 811001 AND 811306
GROUP BY producto
ORDER BY producto;

-- Verificacion estricta de seis fotos por producto demo.
SELECT p.producto, COALESCE(f.cantidad_fotos, 0) AS cantidad_fotos,
       CASE WHEN COALESCE(f.cantidad_fotos, 0) = 6 THEN 'ok' ELSE 'revisar' END AS estado
FROM (VALUES (8010), (8011), (8012), (8013)) AS p(producto)
LEFT JOIN (
    SELECT producto, COUNT(*) AS cantidad_fotos
    FROM fotos
    WHERE producto IN (8010, 8011, 8012, 8013)
      AND identificador BETWEEN 811001 AND 811306
    GROUP BY producto
) f ON f.producto = p.producto
ORDER BY p.producto;

-- Verificar que app_archivos tenga bytes reales para las consignaciones demo.
SELECT s.id AS consignacion_id, s.producto_id, COUNT(a.id) AS archivos,
       COUNT(*) FILTER (WHERE a.content_bytes IS NOT NULL AND octet_length(a.content_bytes) > 0) AS con_bytes
FROM app_solicitudes_consignacion s
LEFT JOIN app_consignacion_fotos cf ON cf.solicitud_id = s.id
LEFT JOIN app_archivos a ON a.id = cf.archivo_id
WHERE s.id IN (16110, 16111, 16112, 16113)
GROUP BY s.id, s.producto_id
ORDER BY s.id;

-- Verificar producto, orden 1..6 y nombres cargados para fotos de consignacion demo.
SELECT s.producto_id,
       s.item_catalogo_id,
       cf.solicitud_id,
       cf.orden,
       cf.archivo_id,
       a.filename_original,
       a.content_type,
       a.size_bytes,
       a.storage_path
FROM app_consignacion_fotos cf
JOIN app_solicitudes_consignacion s ON s.id = cf.solicitud_id
JOIN app_archivos a ON a.id = cf.archivo_id
WHERE cf.solicitud_id IN (16110, 16111, 16112, 16113)
ORDER BY s.producto_id, cf.orden;

-- Pagos de adjudicacion y fallas externas simuladas.
SELECT p.id AS pago_id,
       p.compra_id,
       co.item_catalogo_id,
       co.cuenta_comprador_id,
       c.email AS comprador,
       p.estado AS pago_estado,
       p.error_codigo,
       p.error_detalle,
       co.estado AS compra_estado,
       m.id AS multa_id,
       m.monto AS multa_monto,
       m.vence_at AS multa_vence_at
FROM app_pagos p
JOIN app_compras co ON co.id = p.compra_id
LEFT JOIN app_cuentas c ON c.id = co.cuenta_comprador_id
LEFT JOIN app_multas m ON m.compra_id = co.id
WHERE co.subasta_id = 6011
ORDER BY p.id DESC;

-- Reset demo reutilizable: despues de POST /api/admin/reset/demo debe dar todo en cero/OK.
SELECT 'compradores_activos' AS check_name, COUNT(*) AS total
FROM app_cuentas
WHERE id IN (3001, 3005) AND estado = 'activa'
UNION ALL
SELECT 'medios_principales_consumo_cero', COUNT(*)
FROM app_medios_pago
WHERE id IN (5001, 5010) AND estado = 'verificado' AND consumo_actual = 0
UNION ALL
SELECT 'sin_compras_6011', COUNT(*)
FROM app_compras
WHERE subasta_id = 6011 AND item_catalogo_id IN (9011, 9012, 9013)
UNION ALL
SELECT 'sin_reservas_6011', COUNT(*)
FROM app_reservas_medio_pago r
JOIN app_pujas_live p ON p.id = r.puja_id
WHERE p.subasta_id = 6011
UNION ALL
SELECT 'consignaciones_en_subasta', COUNT(*)
FROM app_solicitudes_consignacion
WHERE id IN (16111, 16112, 16113) AND estado = 'en_subasta';

-- V19: rematador expuesto en detalle de subasta.
SELECT s.identificador AS subasta_id,
       e.titulo,
       p.nombre AS rematador_nombre,
       sub.matricula,
       sub.region
FROM subastas s
JOIN app_subasta_ext e ON e.subasta_id = s.identificador
LEFT JOIN subastadores sub ON sub.identificador = s.subastador
LEFT JOIN personas p ON p.identificador = sub.identificador
WHERE s.identificador IN (6010, 6011);

-- V19: detalle de lote con datos app-owned de consignacion.
SELECT i.identificador AS item_id,
       i.producto AS producto_id,
       s.id AS consignacion_id,
       s.titulo,
       s.descripcion,
       s.historia,
       s.historia_extendida,
       s.artista_disenador,
       s.fecha_objeto,
       s.segmento,
       s.categoria_sugerida
FROM "itemsCatalogo" i
JOIN productos p ON p.identificador = i.producto
LEFT JOIN app_solicitudes_consignacion s
       ON s.item_catalogo_id = i.identificador OR s.producto_id = p.identificador
WHERE i.identificador IN (9011, 9012, 9013)
ORDER BY i.identificador, s.id DESC;

-- V19: consignaciones en custodia fisica.
SELECT id AS consignacion_id,
       estado,
       ubicacion_fisica
FROM app_solicitudes_consignacion
WHERE id IN (16007, 16110, 16111, 16112, 16113)
ORDER BY id;

-- V19: poliza y ubicacion visible para el consignador.
SELECT s.id AS consignacion_id,
       p.seguro AS nro_poliza,
       se.compania,
       se.importe,
       s.ubicacion_fisica
FROM app_solicitudes_consignacion s
JOIN productos p ON p.identificador = s.producto_id
LEFT JOIN seguros se ON se."nroPoliza" = p.seguro
WHERE s.id IN (16007, 16110, 16111, 16112, 16113)
ORDER BY s.id;
