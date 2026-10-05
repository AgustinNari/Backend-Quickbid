-- Validaciones manuales del seed demo de presentacion.
-- Ejecutar en PostgreSQL despues de resetear la base y redeployar el backend.

SELECT COUNT(*) AS flyway_migrations_ok
FROM flyway_schema_history
WHERE success = true;

SELECT version, description, success
FROM flyway_schema_history
ORDER BY installed_rank;

SELECT id, email, estado, categoria_calculada
FROM app_cuentas
WHERE email IN (
    'aprobado@quickbid.demo',
    'comprador2@quickbid.demo',
    'consignador@quickbid.demo',
    'multa@quickbid.demo',
    'bloqueado@quickbid.demo',
    'categoria@quickbid.demo'
)
ORDER BY id;

SELECT COUNT(*) AS compradores_activos
FROM app_cuentas
WHERE email IN ('aprobado@quickbid.demo', 'comprador2@quickbid.demo')
  AND estado = 'activa'
  AND categoria_calculada IN ('plata', 'oro', 'platino');

SELECT m.id, c.email, m.moneda, m.estado, m.limite_monto, m.consumo_actual, m.verificado_hasta
FROM app_medios_pago m
JOIN app_cuentas c ON c.id = m.cuenta_id
WHERE c.email IN ('aprobado@quickbid.demo', 'comprador2@quickbid.demo')
  AND m.moneda = 'ARS'
  AND m.estado = 'verificado'
  AND m.verificado_hasta > CURRENT_TIMESTAMP
ORDER BY m.id;

SELECT s.identificador, e.titulo, e.estado_operativo, e.moneda, s.categoria, s.fecha, s.hora
FROM subastas s
JOIN app_subasta_ext e ON e.subasta_id = s.identificador
WHERE s.identificador IN (6010, 6011);

SELECT c.subasta, COUNT(*) AS items_demo
FROM catalogos c
JOIN "itemsCatalogo" i ON i.catalogo = c.identificador
WHERE c.subasta = 6011
GROUP BY c.subasta
HAVING COUNT(*) >= 3;

SELECT i.identificador AS item_id, i.producto, p.seguro, se.compania
FROM catalogos c
JOIN "itemsCatalogo" i ON i.catalogo = c.identificador
JOIN productos p ON p.identificador = i.producto
LEFT JOIN seguros se ON se."nroPoliza" = p.seguro
WHERE c.subasta = 6011
ORDER BY i.identificador;

SELECT ins.subasta_id, c.email, ins.estado, ins.medio_pago_id
FROM app_inscripciones_subasta ins
JOIN app_cuentas c ON c.id = ins.cuenta_id
WHERE ins.subasta_id = 6011
ORDER BY c.email;

SELECT sc.id, sc.estado, sc.producto_id, sc.item_catalogo_id, sc.subasta_id, p.seguro
FROM app_solicitudes_consignacion sc
LEFT JOIN productos p ON p.identificador = sc.producto_id
WHERE sc.estado IN ('publicada', 'en_subasta', 'vendida', 'liquidada')
ORDER BY sc.id;

SELECT d.id, d.tipo, d.referencia_tipo, d.referencia_id, a.filename_original,
       a.size_bytes, octet_length(a.content_bytes) AS bytes_persistidos
FROM app_documentos d
JOIN app_archivos a ON a.id = d.archivo_id
WHERE d.referencia_tipo IN ('consignacion', 'compra')
ORDER BY d.id;

SELECT COUNT(*) AS reservas_activas_demo_limpio
FROM app_reservas_medio_pago r
JOIN app_pujas_live p ON p.id = r.puja_id
WHERE r.estado = 'activa'
  AND p.subasta_id = 6011;

SELECT sc.id AS consignacion_id, sc.cuenta_id AS consignador_id, buyer.id AS comprador_habilitado_id
FROM app_solicitudes_consignacion sc
JOIN app_cuentas buyer ON buyer.email IN ('aprobado@quickbid.demo', 'comprador2@quickbid.demo')
WHERE sc.subasta_id = 6011
  AND sc.cuenta_id = buyer.id;

SELECT c.email, m.id, m.moneda, m.estado, m.limite_monto, m.verificado_hasta
FROM app_medios_pago m
JOIN app_cuentas c ON c.id = m.cuenta_id
WHERE m.id IN (5010, 5011, 5012, 5013, 5014)
ORDER BY m.id;
