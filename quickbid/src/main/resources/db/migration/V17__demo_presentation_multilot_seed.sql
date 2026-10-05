-- Seed demo complementario para presentacion realista. No modifica migraciones viejas.

INSERT INTO seguros ("nroPoliza", compania, "polizaCombinada", importe) VALUES
    ('POL-DEMO-8001', 'QuickBid Seguros Demo', 'si', 1800.00),
    ('POL-DEMO-8002', 'QuickBid Seguros Demo', 'si', 2200.00),
    ('POL-DEMO-8003', 'QuickBid Seguros Demo', 'si', 3100.00),
    ('POL-DEMO-8004', 'QuickBid Seguros Demo', 'no', 2600.00),
    ('POL-DEMO-8005', 'QuickBid Seguros Demo', 'si', 2000.00),
    ('POL-DEMO-8006', 'QuickBid Seguros Demo', 'si', 4200.00),
    ('POL-DEMO-8007', 'QuickBid Seguros Demo', 'si', 5200.00),
    ('POL-DEMO-8008', 'QuickBid Seguros Demo', 'no', 1700.00),
    ('POL-DEMO-8010', 'QuickBid Seguros Demo', 'si', 2400.00),
    ('POL-DEMO-8011', 'QuickBid Seguros Demo', 'si', 3000.00),
    ('POL-DEMO-8012', 'QuickBid Seguros Demo', 'si', 3600.00),
    ('POL-DEMO-8013', 'QuickBid Seguros Demo', 'si', 4100.00)
ON CONFLICT ("nroPoliza") DO UPDATE SET
    compania = EXCLUDED.compania,
    "polizaCombinada" = EXCLUDED."polizaCombinada",
    importe = EXCLUDED.importe;

UPDATE productos
SET seguro = CASE identificador
    WHEN 8001 THEN 'POL-DEMO-8001'
    WHEN 8002 THEN 'POL-DEMO-8002'
    WHEN 8003 THEN 'POL-DEMO-8003'
    WHEN 8004 THEN 'POL-DEMO-8004'
    WHEN 8005 THEN 'POL-DEMO-8005'
    WHEN 8006 THEN 'POL-DEMO-8006'
    WHEN 8007 THEN 'POL-DEMO-8007'
    WHEN 8008 THEN 'POL-DEMO-8008'
    ELSE seguro
END
WHERE identificador BETWEEN 8001 AND 8008
  AND seguro IS DISTINCT FROM CASE identificador
    WHEN 8001 THEN 'POL-DEMO-8001'
    WHEN 8002 THEN 'POL-DEMO-8002'
    WHEN 8003 THEN 'POL-DEMO-8003'
    WHEN 8004 THEN 'POL-DEMO-8004'
    WHEN 8005 THEN 'POL-DEMO-8005'
    WHEN 8006 THEN 'POL-DEMO-8006'
    WHEN 8007 THEN 'POL-DEMO-8007'
    WHEN 8008 THEN 'POL-DEMO-8008'
    ELSE seguro
END;

INSERT INTO personas (identificador, documento, nombre, direccion, estado) VALUES
    (2005, 'DNI-DEMO-COMPRADOR2', 'Bianca Competidora', 'Av. Demo 500', 'activo'),
    (2006, 'DNI-DEMO-CATEGORIA', 'Ciro Categoria Baja', 'Av. Demo 600', 'activo')
ON CONFLICT (identificador) DO UPDATE SET
    documento = EXCLUDED.documento,
    nombre = EXCLUDED.nombre,
    direccion = EXCLUDED.direccion,
    estado = EXCLUDED.estado;

INSERT INTO clientes (identificador, "numeroPais", admitido, categoria, verificador) VALUES
    (2005, 32, 'si', 'plata', 1001),
    (2006, 32, 'si', 'comun', 1001)
ON CONFLICT (identificador) DO UPDATE SET
    "numeroPais" = EXCLUDED."numeroPais",
    admitido = EXCLUDED.admitido,
    categoria = EXCLUDED.categoria,
    verificador = EXCLUDED.verificador;

INSERT INTO app_cuentas (
    id, persona_id, cliente_id, email, password_hash, estado,
    puntos, categoria_calculada, intentos_login
) VALUES
    (3005, 2005, 2005, 'comprador2@quickbid.demo',
     '$2a$10$V0pWYIdkIsrqbaS46NN5e.b88ECxrQ2of.z/qUHHT7rG.QHhRfJUm',
     'activa', 850, 'plata', 0),
    (3006, 2006, 2006, 'categoria@quickbid.demo',
     '$2a$10$V0pWYIdkIsrqbaS46NN5e.b88ECxrQ2of.z/qUHHT7rG.QHhRfJUm',
     'activa', 80, 'comun', 0)
ON CONFLICT (id) DO UPDATE SET
    persona_id = EXCLUDED.persona_id,
    cliente_id = EXCLUDED.cliente_id,
    email = EXCLUDED.email,
    password_hash = EXCLUDED.password_hash,
    estado = EXCLUDED.estado,
    puntos = EXCLUDED.puntos,
    categoria_calculada = EXCLUDED.categoria_calculada,
    intentos_login = EXCLUDED.intentos_login,
    updated_at = CURRENT_TIMESTAMP;

UPDATE app_pujas_live
SET estado = 'superada'
WHERE id = 12501
  AND estado = 'aceptada';

UPDATE app_reservas_medio_pago
SET estado = 'liberada', released_at = COALESCE(released_at, CURRENT_TIMESTAMP)
WHERE puja_id = 12501
  AND estado = 'activa';

UPDATE app_medios_pago
SET consumo_actual = 0, updated_at = CURRENT_TIMESTAMP
WHERE id = 5001
  AND consumo_actual <> 0;

INSERT INTO app_medios_pago (
    id, cuenta_id, tipo, moneda, estado, principal, nacional, alias_visible,
    ultimos_4, titular, hash_identificador, limite_monto, consumo_actual,
    saldo_garantia, verificado_hasta, verificado_por_empleado_id
) VALUES
    (5010, 3005, 'tarjeta', 'ARS', 'verificado', true, true, 'Visa ARS comprador 2 terminada en 2222',
     '2222', 'Bianca Competidora', 'demo-token-card-ars-comprador2', 650000.00, 0.00,
     NULL, CURRENT_TIMESTAMP + INTERVAL '7 days', 1003),
    (5011, 3005, 'tarjeta', 'ARS', 'vencido', false, true, 'Tarjeta ARS vencida demo',
     '1111', 'Bianca Competidora', 'demo-token-card-ars-vencida', 300000.00, 0.00,
     NULL, CURRENT_TIMESTAMP - INTERVAL '1 day', 1003),
    (5012, 3005, 'tarjeta', 'ARS', 'pendiente_verificacion', false, true, 'Tarjeta ARS pendiente demo',
     '1212', 'Bianca Competidora', 'demo-token-card-ars-pendiente', 250000.00, 0.00,
     NULL, NULL, NULL),
    (5013, 3005, 'tarjeta', 'USD', 'rechazado', false, false, 'Tarjeta USD rechazada demo',
     '1313', 'Bianca Competidora', 'demo-token-card-usd-rechazada', 5000.00, 0.00,
     NULL, NULL, 1003),
    (5014, 3006, 'tarjeta', 'ARS', 'verificado', true, true, 'Visa ARS limite insuficiente',
     '1414', 'Ciro Categoria Baja', 'demo-token-card-ars-insuficiente', 1000.00, 0.00,
     NULL, CURRENT_TIMESTAMP + INTERVAL '7 days', 1003)
ON CONFLICT (id) DO UPDATE SET
    cuenta_id = EXCLUDED.cuenta_id,
    tipo = EXCLUDED.tipo,
    moneda = EXCLUDED.moneda,
    estado = EXCLUDED.estado,
    principal = EXCLUDED.principal,
    nacional = EXCLUDED.nacional,
    alias_visible = EXCLUDED.alias_visible,
    ultimos_4 = EXCLUDED.ultimos_4,
    titular = EXCLUDED.titular,
    hash_identificador = EXCLUDED.hash_identificador,
    limite_monto = EXCLUDED.limite_monto,
    consumo_actual = EXCLUDED.consumo_actual,
    saldo_garantia = EXCLUDED.saldo_garantia,
    verificado_hasta = EXCLUDED.verificado_hasta,
    verificado_por_empleado_id = EXCLUDED.verificado_por_empleado_id,
    updated_at = CURRENT_TIMESTAMP,
    deleted_at = NULL;

INSERT INTO app_tarjetas (medio_pago_id, marca, vencimiento_mes, vencimiento_anio) VALUES
    (5010, 'Visa', 12, 2030),
    (5011, 'Visa', 1, 2030),
    (5012, 'Visa', 2, 2030),
    (5013, 'Visa', 3, 2030),
    (5014, 'Visa', 4, 2030)
ON CONFLICT (medio_pago_id) DO UPDATE SET
    marca = EXCLUDED.marca,
    vencimiento_mes = EXCLUDED.vencimiento_mes,
    vencimiento_anio = EXCLUDED.vencimiento_anio;

INSERT INTO app_direcciones_envio (
    id, cuenta_id, alias, destinatario, calle, numero, codigo_postal,
    localidad, provincia, pais, telefono, principal
) VALUES
    (5102, 3005, 'Casa', 'Bianca Competidora', 'Av. Demo', '500', 'C1000', 'Buenos Aires', 'Buenos Aires', 'Argentina', '+54 11 5555 0202', true)
ON CONFLICT (id) DO UPDATE SET
    cuenta_id = EXCLUDED.cuenta_id,
    alias = EXCLUDED.alias,
    destinatario = EXCLUDED.destinatario,
    calle = EXCLUDED.calle,
    numero = EXCLUDED.numero,
    codigo_postal = EXCLUDED.codigo_postal,
    localidad = EXCLUDED.localidad,
    provincia = EXCLUDED.provincia,
    pais = EXCLUDED.pais,
    telefono = EXCLUDED.telefono,
    principal = EXCLUDED.principal,
    updated_at = CURRENT_TIMESTAMP,
    deleted_at = NULL;

INSERT INTO subastas (
    identificador, fecha, hora, estado, subastador, ubicacion,
    "capacidadAsistentes", "tieneDeposito", "seguridadPropia", categoria
) VALUES
    (6010,
     CASE WHEN CURRENT_TIME < TIME '21:00' THEN CURRENT_DATE ELSE CURRENT_DATE + 1 END,
     CASE WHEN CURRENT_TIME < TIME '21:00' THEN (CURRENT_TIME + INTERVAL '2 hours')::time ELSE TIME '10:00' END,
     'abierta',
     (SELECT identificador FROM personas WHERE documento = 'EMPRESA-SUB-001'),
     'Casa central QuickBid',
     120, 'si', 'si', 'plata'),
    (6011,
     CASE WHEN CURRENT_TIME < TIME '21:00' THEN CURRENT_DATE ELSE CURRENT_DATE + 1 END,
     CASE WHEN CURRENT_TIME < TIME '21:00' THEN (CURRENT_TIME + INTERVAL '2 hours')::time ELSE TIME '10:30' END,
     'abierta',
     (SELECT identificador FROM personas WHERE documento = 'EMPRESA-SUB-001'),
     'Sala demo QuickBid',
     150, 'si', 'si', 'plata')
ON CONFLICT (identificador) DO UPDATE SET
    fecha = EXCLUDED.fecha,
    hora = EXCLUDED.hora,
    estado = EXCLUDED.estado,
    subastador = EXCLUDED.subastador,
    ubicacion = EXCLUDED.ubicacion,
    "capacidadAsistentes" = EXCLUDED."capacidadAsistentes",
    "tieneDeposito" = EXCLUDED."tieneDeposito",
    "seguridadPropia" = EXCLUDED."seguridadPropia",
    categoria = EXCLUDED.categoria;

INSERT INTO app_subasta_ext (
    subasta_id, titulo, descripcion, moneda, segmento, estado_operativo,
    permite_inscripcion_online
) VALUES
    (6010, 'Demo presentacion inscripcion cercana',
     'Subasta cercana para mostrar listado, detalle e inscripcion aprobada antes del vivo.',
     'ARS', 'demo', 'programada', true),
    (6011, 'Subasta demo en vivo QuickBid',
     'Escenario limpio multi-lote para dos compradores, pujas alternadas, cierre de lote y cierre final.',
     'ARS', 'demo', 'abierta', true)
ON CONFLICT (subasta_id) DO UPDATE SET
    titulo = EXCLUDED.titulo,
    descripcion = EXCLUDED.descripcion,
    moneda = EXCLUDED.moneda,
    segmento = EXCLUDED.segmento,
    estado_operativo = EXCLUDED.estado_operativo,
    permite_inscripcion_online = EXCLUDED.permite_inscripcion_online,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO productos (
    identificador, fecha, disponible, "descripcionCatalogo", "descripcionCompleta",
    revisor, duenio, seguro
) VALUES
    (8010, CURRENT_DATE, 'si', 'Lampara italiana restaurada', 'demo/docs/producto-8010.pdf', 1002, 2004, 'POL-DEMO-8010'),
    (8011, CURRENT_DATE, 'si', 'Sillon escandinavo de roble', 'demo/docs/producto-8011.pdf', 1002, 2004, 'POL-DEMO-8011'),
    (8012, CURRENT_DATE, 'si', 'Juego de vajilla art deco', 'demo/docs/producto-8012.pdf', 1002, 2004, 'POL-DEMO-8012'),
    (8013, CURRENT_DATE, 'si', 'Reloj de mesa coleccionable', 'demo/docs/producto-8013.pdf', 1002, 2004, 'POL-DEMO-8013')
ON CONFLICT (identificador) DO UPDATE SET
    fecha = EXCLUDED.fecha,
    disponible = EXCLUDED.disponible,
    "descripcionCatalogo" = EXCLUDED."descripcionCatalogo",
    "descripcionCompleta" = EXCLUDED."descripcionCompleta",
    revisor = EXCLUDED.revisor,
    duenio = EXCLUDED.duenio,
    seguro = EXCLUDED.seguro;

INSERT INTO fotos (identificador, producto, foto) VALUES
    (8110, 8010, decode('89504E470D0A1A0A', 'hex')),
    (8111, 8011, decode('89504E470D0A1A0A', 'hex')),
    (8112, 8012, decode('89504E470D0A1A0A', 'hex')),
    (8113, 8013, decode('89504E470D0A1A0A', 'hex'))
ON CONFLICT (identificador) DO UPDATE SET
    producto = EXCLUDED.producto,
    foto = EXCLUDED.foto;

INSERT INTO catalogos (identificador, descripcion, subasta, responsable) VALUES
    (7010, 'Catalogo demo inscripcion cercana', 6010, 1004),
    (7011, 'Catalogo demo en vivo multi-lote', 6011, 1004)
ON CONFLICT (identificador) DO UPDATE SET
    descripcion = EXCLUDED.descripcion,
    subasta = EXCLUDED.subasta,
    responsable = EXCLUDED.responsable;

INSERT INTO "itemsCatalogo" (
    identificador, catalogo, producto, "precioBase", comision, subastado
) VALUES
    (9010, 7010, 8010, 45000.00, 4500.00, 'no'),
    (9011, 7011, 8011, 50000.00, 5000.00, 'no'),
    (9012, 7011, 8012, 70000.00, 7000.00, 'no'),
    (9013, 7011, 8013, 90000.00, 9000.00, 'no')
ON CONFLICT (identificador) DO UPDATE SET
    catalogo = EXCLUDED.catalogo,
    producto = EXCLUDED.producto,
    "precioBase" = EXCLUDED."precioBase",
    comision = EXCLUDED.comision,
    subastado = EXCLUDED.subastado;

INSERT INTO app_subasta_estado_vivo (
    subasta_id, item_catalogo_activo_id, version, usuarios_conectados,
    lote_iniciado_at, lote_finaliza_estimado_at, retencion_hasta,
    proximo_lote_programado_at, subasta_finaliza_programado_at
) VALUES
    (6010, NULL, 0, 0, NULL, NULL, NULL, NULL, NULL),
    (6011, NULL, 0, 0, NULL, NULL, NULL, NULL, NULL)
ON CONFLICT (subasta_id) DO UPDATE SET
    item_catalogo_activo_id = EXCLUDED.item_catalogo_activo_id,
    version = EXCLUDED.version,
    usuarios_conectados = EXCLUDED.usuarios_conectados,
    lote_iniciado_at = EXCLUDED.lote_iniciado_at,
    lote_finaliza_estimado_at = EXCLUDED.lote_finaliza_estimado_at,
    retencion_hasta = EXCLUDED.retencion_hasta,
    proximo_lote_programado_at = EXCLUDED.proximo_lote_programado_at,
    subasta_finaliza_programado_at = EXCLUDED.subasta_finaliza_programado_at,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO app_inscripciones_subasta (
    id, subasta_id, cuenta_id, medio_pago_id, estado
) VALUES
    (10010, 6010, 3001, 5001, 'aprobada'),
    (10011, 6010, 3005, 5010, 'aprobada'),
    (10012, 6011, 3001, 5001, 'aprobada'),
    (10013, 6011, 3005, 5010, 'aprobada'),
    (10014, 6011, 3006, 5014, 'rechazada')
ON CONFLICT (id) DO UPDATE SET
    subasta_id = EXCLUDED.subasta_id,
    cuenta_id = EXCLUDED.cuenta_id,
    medio_pago_id = EXCLUDED.medio_pago_id,
    estado = EXCLUDED.estado,
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO app_solicitudes_consignacion (
    id, cuenta_id, cliente_id, producto_id, item_catalogo_id, subasta_id,
    titulo, descripcion, categoria_sugerida, segmento, historia, artista_disenador,
    fecha_objeto, declaracion_propiedad, acepta_devolucion_con_cargo, estado,
    requiere_documentacion_origen, motivo_rechazo, revisor_empleado_id,
    valor_base_propuesto, moneda_propuesta, comision_comprador_pct,
    comision_vendedor_pct, acuerdo_texto, acuerdo_enviado_at, acuerdo_aceptado_at
) VALUES
    (16110, 3004, 2004, 8010, 9010, 6010,
     'Lampara italiana restaurada', 'Producto consignado para la subasta cercana demo.', 'plata', 'iluminacion',
     'Restaurada por taller local.', 'Taller Demo', '1975', true, true, 'publicada',
     false, NULL, 1002, 45000.00, 'ARS', 10.00, 10.00,
     'Acuerdo demo aceptado con poliza asociada.', CURRENT_TIMESTAMP - INTERVAL '2 days', CURRENT_TIMESTAMP - INTERVAL '1 day'),
    (16111, 3004, 2004, 8011, 9011, 6011,
     'Sillon escandinavo de roble', 'Primer lote del escenario live multi-lote.', 'plata', 'mobiliario',
     'Pieza restaurada para presentacion.', 'Disenador Demo', '1968', true, true, 'en_subasta',
     false, NULL, 1002, 50000.00, 'ARS', 10.00, 10.00,
     'Acuerdo demo aceptado con poliza asociada.', CURRENT_TIMESTAMP - INTERVAL '2 days', CURRENT_TIMESTAMP - INTERVAL '1 day'),
    (16112, 3004, 2004, 8012, 9012, 6011,
     'Juego de vajilla art deco', 'Segundo lote del escenario live multi-lote.', 'plata', 'decoracion',
     'Conjunto completo para mesa de seis.', 'Casa Demo', '1935', true, true, 'en_subasta',
     false, NULL, 1002, 70000.00, 'ARS', 10.00, 10.00,
     'Acuerdo demo aceptado con poliza asociada.', CURRENT_TIMESTAMP - INTERVAL '2 days', CURRENT_TIMESTAMP - INTERVAL '1 day'),
    (16113, 3004, 2004, 8013, 9013, 6011,
     'Reloj de mesa coleccionable', 'Tercer lote del escenario live multi-lote.', 'plata', 'coleccion',
     'Reloj mecanico con llave original.', 'Relojero Demo', '1940', true, true, 'en_subasta',
     false, NULL, 1002, 90000.00, 'ARS', 10.00, 10.00,
     'Acuerdo demo aceptado con poliza asociada.', CURRENT_TIMESTAMP - INTERVAL '2 days', CURRENT_TIMESTAMP - INTERVAL '1 day')
ON CONFLICT (id) DO UPDATE SET
    cuenta_id = EXCLUDED.cuenta_id,
    cliente_id = EXCLUDED.cliente_id,
    producto_id = EXCLUDED.producto_id,
    item_catalogo_id = EXCLUDED.item_catalogo_id,
    subasta_id = EXCLUDED.subasta_id,
    titulo = EXCLUDED.titulo,
    descripcion = EXCLUDED.descripcion,
    categoria_sugerida = EXCLUDED.categoria_sugerida,
    segmento = EXCLUDED.segmento,
    historia = EXCLUDED.historia,
    artista_disenador = EXCLUDED.artista_disenador,
    fecha_objeto = EXCLUDED.fecha_objeto,
    declaracion_propiedad = EXCLUDED.declaracion_propiedad,
    acepta_devolucion_con_cargo = EXCLUDED.acepta_devolucion_con_cargo,
    estado = EXCLUDED.estado,
    requiere_documentacion_origen = EXCLUDED.requiere_documentacion_origen,
    motivo_rechazo = EXCLUDED.motivo_rechazo,
    revisor_empleado_id = EXCLUDED.revisor_empleado_id,
    valor_base_propuesto = EXCLUDED.valor_base_propuesto,
    moneda_propuesta = EXCLUDED.moneda_propuesta,
    comision_comprador_pct = EXCLUDED.comision_comprador_pct,
    comision_vendedor_pct = EXCLUDED.comision_vendedor_pct,
    acuerdo_texto = EXCLUDED.acuerdo_texto,
    acuerdo_enviado_at = EXCLUDED.acuerdo_enviado_at,
    acuerdo_aceptado_at = EXCLUDED.acuerdo_aceptado_at,
    updated_at = CURRENT_TIMESTAMP;

WITH pdfs(id, owner_cuenta_id, tipo_contexto, filename_original, storage_path, checksum, bytes) AS (
    VALUES
        (4201::bigint, 3004::bigint, 'documento_consignacion', 'poliza-8010-demo.pdf', 'demo/documentos/poliza-8010.pdf', 'demo-poliza-8010',
         decode('255044462D312E340A312030206F626A3C3C202F54797065202F436174616C6F67202F5061676573203220302052203E3E656E646F626A0A322030206F626A3C3C202F54797065202F5061676573202F4B696473205B33203020525D202F436F756E742031203E3E656E646F626A0A332030206F626A3C3C202F54797065202F50616765202F506172656E74203220302052202F4D65646961426F78205B30203020333030203134345D202F436F6E74656E7473203420302052202F5265736F75726365733C3C202F466F6E743C3C202F4631203520302052203E3E203E3E203E3E656E646F626A0A342030206F626A3C3C202F4C656E677468203832203E3E73747265616D0A4254202F4631203132205466203330203130302054642028466163747572612064656D6F20517569636B426964202D2073696E2076616F722066697363616C2920546A2030202D323020546420284669787475726520504446207061726120707275656261732920546A2045540A656E6473747265616D20656E646F626A0A352030206F626A3C3C202F54797065202F466F6E74202F53756274797065202F5479706531202F42617365466F6E74202F48656C766574696361203E3E656E646F626A0A787265660A3020360A303030303030303030302036353533352066200A30303030303030303039203030303030206E200A30303030303030303538203030303030206E200A30303030303030313135203030303030206E200A30303030303030323530203030303030206E200A30303030303030333833203030303030206E200A747261696C65723C3C202F53697A652036202F526F6F74203120302052203E3E0A7374617274787265660A3435330A2525454F460A', 'hex')),
        (4202::bigint, 3004::bigint, 'documento_consignacion', 'poliza-8011-demo.pdf', 'demo/documentos/poliza-8011.pdf', 'demo-poliza-8011',
         decode('255044462D312E340A312030206F626A3C3C202F54797065202F436174616C6F67202F5061676573203220302052203E3E656E646F626A0A322030206F626A3C3C202F54797065202F5061676573202F4B696473205B33203020525D202F436F756E742031203E3E656E646F626A0A332030206F626A3C3C202F54797065202F50616765202F506172656E74203220302052202F4D65646961426F78205B30203020333030203134345D202F436F6E74656E7473203420302052202F5265736F75726365733C3C202F466F6E743C3C202F4631203520302052203E3E203E3E203E3E656E646F626A0A342030206F626A3C3C202F4C656E677468203832203E3E73747265616D0A4254202F4631203132205466203330203130302054642028466163747572612064656D6F20517569636B426964202D2073696E2076616F722066697363616C2920546A2030202D323020546420284669787475726520504446207061726120707275656261732920546A2045540A656E6473747265616D20656E646F626A0A352030206F626A3C3C202F54797065202F466F6E74202F53756274797065202F5479706531202F42617365466F6E74202F48656C766574696361203E3E656E646F626A0A787265660A3020360A303030303030303030302036353533352066200A30303030303030303039203030303030206E200A30303030303030303538203030303030206E200A30303030303030313135203030303030206E200A30303030303030323530203030303030206E200A30303030303030333833203030303030206E200A747261696C65723C3C202F53697A652036202F526F6F74203120302052203E3E0A7374617274787265660A3435330A2525454F460A', 'hex')),
        (4203::bigint, 3004::bigint, 'documento_consignacion', 'poliza-8012-demo.pdf', 'demo/documentos/poliza-8012.pdf', 'demo-poliza-8012',
         decode('255044462D312E340A312030206F626A3C3C202F54797065202F436174616C6F67202F5061676573203220302052203E3E656E646F626A0A322030206F626A3C3C202F54797065202F5061676573202F4B696473205B33203020525D202F436F756E742031203E3E656E646F626A0A332030206F626A3C3C202F54797065202F50616765202F506172656E74203220302052202F4D65646961426F78205B30203020333030203134345D202F436F6E74656E7473203420302052202F5265736F75726365733C3C202F466F6E743C3C202F4631203520302052203E3E203E3E203E3E656E646F626A0A342030206F626A3C3C202F4C656E677468203832203E3E73747265616D0A4254202F4631203132205466203330203130302054642028466163747572612064656D6F20517569636B426964202D2073696E2076616F722066697363616C2920546A2030202D323020546420284669787475726520504446207061726120707275656261732920546A2045540A656E6473747265616D20656E646F626A0A352030206F626A3C3C202F54797065202F466F6E74202F53756274797065202F5479706531202F42617365466F6E74202F48656C766574696361203E3E656E646F626A0A787265660A3020360A303030303030303030302036353533352066200A30303030303030303039203030303030206E200A30303030303030303538203030303030206E200A30303030303030313135203030303030206E200A30303030303030323530203030303030206E200A30303030303030333833203030303030206E200A747261696C65723C3C202F53697A652036202F526F6F74203120302052203E3E0A7374617274787265660A3435330A2525454F460A', 'hex')),
        (4204::bigint, 3004::bigint, 'documento_consignacion', 'poliza-8013-demo.pdf', 'demo/documentos/poliza-8013.pdf', 'demo-poliza-8013',
         decode('255044462D312E340A312030206F626A3C3C202F54797065202F436174616C6F67202F5061676573203220302052203E3E656E646F626A0A322030206F626A3C3C202F54797065202F5061676573202F4B696473205B33203020525D202F436F756E742031203E3E656E646F626A0A332030206F626A3C3C202F54797065202F50616765202F506172656E74203220302052202F4D65646961426F78205B30203020333030203134345D202F436F6E74656E7473203420302052202F5265736F75726365733C3C202F466F6E743C3C202F4631203520302052203E3E203E3E203E3E656E646F626A0A342030206F626A3C3C202F4C656E677468203832203E3E73747265616D0A4254202F4631203132205466203330203130302054642028466163747572612064656D6F20517569636B426964202D2073696E2076616F722066697363616C2920546A2030202D323020546420284669787475726520504446207061726120707275656261732920546A2045540A656E6473747265616D20656E646F626A0A352030206F626A3C3C202F54797065202F466F6E74202F53756274797065202F5479706531202F42617365466F6E74202F48656C766574696361203E3E656E646F626A0A787265660A3020360A303030303030303030302036353533352066200A30303030303030303039203030303030206E200A30303030303030303538203030303030206E200A30303030303030313135203030303030206E200A30303030303030323530203030303030206E200A30303030303030333833203030303030206E200A747261696C65723C3C202F53697A652036202F526F6F74203120302052203E3E0A7374617274787265660A3435330A2525454F460A', 'hex')),
        (4205::bigint, 3004::bigint, 'documento_consignacion', 'acuerdo-demo-multilote.pdf', 'demo/documentos/acuerdo-multilote.pdf', 'demo-acuerdo-multilote',
         decode('255044462D312E340A312030206F626A3C3C202F54797065202F436174616C6F67202F5061676573203220302052203E3E656E646F626A0A322030206F626A3C3C202F54797065202F5061676573202F4B696473205B33203020525D202F436F756E742031203E3E656E646F626A0A332030206F626A3C3C202F54797065202F50616765202F506172656E74203220302052202F4D65646961426F78205B30203020333030203134345D202F436F6E74656E7473203420302052202F5265736F75726365733C3C202F466F6E743C3C202F4631203520302052203E3E203E3E203E3E656E646F626A0A342030206F626A3C3C202F4C656E677468203832203E3E73747265616D0A4254202F4631203132205466203330203130302054642028466163747572612064656D6F20517569636B426964202D2073696E2076616F722066697363616C2920546A2030202D323020546420284669787475726520504446207061726120707275656261732920546A2045540A656E6473747265616D20656E646F626A0A352030206F626A3C3C202F54797065202F466F6E74202F53756274797065202F5479706531202F42617365466F6E74202F48656C766574696361203E3E656E646F626A0A787265660A3020360A303030303030303030302036353533352066200A30303030303030303039203030303030206E200A30303030303030303538203030303030206E200A30303030303030313135203030303030206E200A30303030303030323530203030303030206E200A30303030303030333833203030303030206E200A747261696C65723C3C202F53697A652036202F526F6F74203120302052203E3E0A7374617274787265660A3435330A2525454F460A', 'hex'))
)
INSERT INTO app_archivos (
    id, owner_cuenta_id, tipo_contexto, filename_original, content_type,
    size_bytes, storage_path, checksum, content_bytes
)
SELECT id, owner_cuenta_id, tipo_contexto, filename_original, 'application/pdf',
       octet_length(bytes), storage_path, checksum, bytes
FROM pdfs
ON CONFLICT (id) DO UPDATE SET
    owner_cuenta_id = EXCLUDED.owner_cuenta_id,
    tipo_contexto = EXCLUDED.tipo_contexto,
    filename_original = EXCLUDED.filename_original,
    content_type = EXCLUDED.content_type,
    size_bytes = EXCLUDED.size_bytes,
    storage_path = EXCLUDED.storage_path,
    checksum = EXCLUDED.checksum,
    content_bytes = EXCLUDED.content_bytes;

WITH existing_docs(id, bytes) AS (
    VALUES
        (4020::bigint, decode('255044462D312E340A312030206F626A3C3C202F54797065202F436174616C6F67202F5061676573203220302052203E3E656E646F626A0A322030206F626A3C3C202F54797065202F5061676573202F4B696473205B33203020525D202F436F756E742031203E3E656E646F626A0A332030206F626A3C3C202F54797065202F50616765202F506172656E74203220302052202F4D65646961426F78205B30203020333030203134345D202F436F6E74656E7473203420302052202F5265736F75726365733C3C202F466F6E743C3C202F4631203520302052203E3E203E3E203E3E656E646F626A0A342030206F626A3C3C202F4C656E677468203832203E3E73747265616D0A4254202F4631203132205466203330203130302054642028466163747572612064656D6F20517569636B426964202D2073696E2076616F722066697363616C2920546A2030202D323020546420284669787475726520504446207061726120707275656261732920546A2045540A656E6473747265616D20656E646F626A0A352030206F626A3C3C202F54797065202F466F6E74202F53756274797065202F5479706531202F42617365466F6E74202F48656C766574696361203E3E656E646F626A0A787265660A3020360A303030303030303030302036353533352066200A30303030303030303039203030303030206E200A30303030303030303538203030303030206E200A30303030303030313135203030303030206E200A30303030303030323530203030303030206E200A30303030303030333833203030303030206E200A747261696C65723C3C202F53697A652036202F526F6F74203120302052203E3E0A7374617274787265660A3435330A2525454F460A', 'hex')),
        (4030::bigint, decode('255044462D312E340A312030206F626A3C3C202F54797065202F436174616C6F67202F5061676573203220302052203E3E656E646F626A0A322030206F626A3C3C202F54797065202F5061676573202F4B696473205B33203020525D202F436F756E742031203E3E656E646F626A0A332030206F626A3C3C202F54797065202F50616765202F506172656E74203220302052202F4D65646961426F78205B30203020333030203134345D202F436F6E74656E7473203420302052202F5265736F75726365733C3C202F466F6E743C3C202F4631203520302052203E3E203E3E203E3E656E646F626A0A342030206F626A3C3C202F4C656E677468203832203E3E73747265616D0A4254202F4631203132205466203330203130302054642028466163747572612064656D6F20517569636B426964202D2073696E2076616F722066697363616C2920546A2030202D323020546420284669787475726520504446207061726120707275656261732920546A2045540A656E6473747265616D20656E646F626A0A352030206F626A3C3C202F54797065202F466F6E74202F53756274797065202F5479706531202F42617365466F6E74202F48656C766574696361203E3E656E646F626A0A787265660A3020360A303030303030303030302036353533352066200A30303030303030303039203030303030206E200A30303030303030303538203030303030206E200A30303030303030313135203030303030206E200A30303030303030323530203030303030206E200A30303030303030333833203030303030206E200A747261696C65723C3C202F53697A652036202F526F6F74203120302052203E3E0A7374617274787265660A3435330A2525454F460A', 'hex'))
)
UPDATE app_archivos a
SET size_bytes = octet_length(d.bytes),
    content_type = 'application/pdf',
    content_bytes = d.bytes
FROM existing_docs d
WHERE a.id = d.id
  AND (a.content_bytes IS NULL OR a.size_bytes IS DISTINCT FROM octet_length(d.bytes));

INSERT INTO app_documentos (
    id, tipo, referencia_tipo, referencia_id, archivo_id, estado
) VALUES
    (17410, 'poliza_seguro', 'consignacion', 16110, 4201, 'disponible'),
    (17411, 'poliza_seguro', 'consignacion', 16111, 4202, 'disponible'),
    (17412, 'poliza_seguro', 'consignacion', 16112, 4203, 'disponible'),
    (17413, 'poliza_seguro', 'consignacion', 16113, 4204, 'disponible'),
    (17414, 'acuerdo_consignacion', 'consignacion', 16111, 4205, 'disponible')
ON CONFLICT (id) DO UPDATE SET
    tipo = EXCLUDED.tipo,
    referencia_tipo = EXCLUDED.referencia_tipo,
    referencia_id = EXCLUDED.referencia_id,
    archivo_id = EXCLUDED.archivo_id,
    estado = EXCLUDED.estado;

INSERT INTO app_notificaciones (
    id, cuenta_id, tipo, titulo, descripcion, referencia_tipo, referencia_id, leida
) VALUES
    (18010, 3001, 'demo_lista', 'Subasta demo lista', 'Ya estas inscripto en la subasta demo multi-lote.', 'subasta', 6011, false),
    (18011, 3005, 'demo_lista', 'Subasta demo lista', 'Ya estas inscripta en la subasta demo multi-lote.', 'subasta', 6011, false),
    (18012, 3004, 'poliza_disponible', 'Polizas demo disponibles', 'Los lotes demo tienen poliza y acuerdo descargable.', 'consignacion', 16111, false)
ON CONFLICT (id) DO UPDATE SET
    cuenta_id = EXCLUDED.cuenta_id,
    tipo = EXCLUDED.tipo,
    titulo = EXCLUDED.titulo,
    descripcion = EXCLUDED.descripcion,
    referencia_tipo = EXCLUDED.referencia_tipo,
    referencia_id = EXCLUDED.referencia_id,
    leida = EXCLUDED.leida;

INSERT INTO app_auditoria (
    id, actor_tipo, actor_id, accion, entidad_tipo, entidad_id, metadata_json
) VALUES
    (19010, 'sistema', NULL, 'SEED_DEMO_PRESENTACION_CREADO', 'demo', NULL, '{"migration":"V17__demo_presentation_multilot_seed.sql","subastaDemo":6011}')
ON CONFLICT (id) DO UPDATE SET
    actor_tipo = EXCLUDED.actor_tipo,
    actor_id = EXCLUDED.actor_id,
    accion = EXCLUDED.accion,
    entidad_tipo = EXCLUDED.entidad_tipo,
    entidad_id = EXCLUDED.entidad_id,
    metadata_json = EXCLUDED.metadata_json;

SELECT setval(pg_get_serial_sequence('personas', 'identificador'), (SELECT max(identificador) FROM personas), true);
SELECT setval(pg_get_serial_sequence('subastas', 'identificador'), (SELECT max(identificador) FROM subastas), true);
SELECT setval(pg_get_serial_sequence('productos', 'identificador'), (SELECT max(identificador) FROM productos), true);
SELECT setval(pg_get_serial_sequence('fotos', 'identificador'), (SELECT max(identificador) FROM fotos), true);
SELECT setval(pg_get_serial_sequence('catalogos', 'identificador'), (SELECT max(identificador) FROM catalogos), true);
SELECT setval(pg_get_serial_sequence('"itemsCatalogo"', 'identificador'), (SELECT max(identificador) FROM "itemsCatalogo"), true);
SELECT setval(pg_get_serial_sequence('app_cuentas', 'id'), (SELECT max(id) FROM app_cuentas), true);
SELECT setval(pg_get_serial_sequence('app_archivos', 'id'), (SELECT max(id) FROM app_archivos), true);
SELECT setval(pg_get_serial_sequence('app_medios_pago', 'id'), (SELECT max(id) FROM app_medios_pago), true);
SELECT setval(pg_get_serial_sequence('app_direcciones_envio', 'id'), (SELECT max(id) FROM app_direcciones_envio), true);
SELECT setval(pg_get_serial_sequence('app_inscripciones_subasta', 'id'), (SELECT max(id) FROM app_inscripciones_subasta), true);
SELECT setval(pg_get_serial_sequence('app_solicitudes_consignacion', 'id'), (SELECT max(id) FROM app_solicitudes_consignacion), true);
SELECT setval(pg_get_serial_sequence('app_documentos', 'id'), (SELECT max(id) FROM app_documentos), true);
SELECT setval(pg_get_serial_sequence('app_notificaciones', 'id'), (SELECT max(id) FROM app_notificaciones), true);
SELECT setval(pg_get_serial_sequence('app_auditoria', 'id'), (SELECT max(id) FROM app_auditoria), true);
