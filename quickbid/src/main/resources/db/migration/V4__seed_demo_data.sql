-- Functional demo scenarios for local development.
-- All operative demo accounts use the BCrypt hash for: Demo123!

INSERT INTO personas (identificador, documento, nombre, direccion, estado) VALUES
    (2001, 'DNI-DEMO-APROBADO', 'Ana Aprobada', 'Av. Demo 100', 'activo'),
    (2002, 'DNI-DEMO-MULTA', 'Bruno Restringido', 'Av. Demo 200', 'activo'),
    (2003, 'DNI-DEMO-BLOQUEADO', 'Carla Bloqueada', 'Av. Demo 300', 'activo'),
    (2004, 'DNI-DEMO-CONSIGNADOR', 'Diego Consignador', 'Av. Demo 400', 'activo');

INSERT INTO clientes (identificador, "numeroPais", admitido, categoria, verificador) VALUES
    (2001, 32, 'si', 'plata', 1001),
    (2002, 32, 'si', 'especial', 1001),
    (2003, 32, 'si', 'comun', 1001),
    (2004, 32, 'si', 'oro', 1001);

INSERT INTO duenios (
    identificador, "numeroPais", "verificacionFinanciera",
    "verificacionJudicial", "calificacionRiesgo", verificador
) VALUES
    (2004, 32, 'si', 'si', 2, 1002);

INSERT INTO app_cuentas (
    id, persona_id, cliente_id, email, password_hash, estado,
    puntos, categoria_calculada, intentos_login
) VALUES
    (3001, 2001, 2001, 'aprobado@quickbid.demo',
     '$2a$10$V0pWYIdkIsrqbaS46NN5e.b88ECxrQ2of.z/qUHHT7rG.QHhRfJUm',
     'activa', 900, 'plata', 0),
    (3002, 2002, 2002, 'multa@quickbid.demo',
     '$2a$10$V0pWYIdkIsrqbaS46NN5e.b88ECxrQ2of.z/qUHHT7rG.QHhRfJUm',
     'restriccion_multa', 300, 'especial', 0),
    (3003, 2003, 2003, 'bloqueado@quickbid.demo',
     '$2a$10$V0pWYIdkIsrqbaS46NN5e.b88ECxrQ2of.z/qUHHT7rG.QHhRfJUm',
     'bloqueada_permanente', 0, 'comun', 0),
    (3004, 2004, 2004, 'consignador@quickbid.demo',
     '$2a$10$V0pWYIdkIsrqbaS46NN5e.b88ECxrQ2of.z/qUHHT7rG.QHhRfJUm',
     'activa', 1800, 'oro', 0);

INSERT INTO app_archivos (
    id, owner_cuenta_id, tipo_contexto, filename_original, content_type,
    size_bytes, storage_path, checksum
) VALUES
    (4001, NULL, 'dni', 'pendiente-dni-frente.png', 'image/png', 24, 'demo/dni/pendiente-frente.png', 'demo-checksum-dni-frente'),
    (4002, NULL, 'dni', 'pendiente-dni-dorso.png', 'image/png', 24, 'demo/dni/pendiente-dorso.png', 'demo-checksum-dni-dorso'),
    (4010, 3001, 'cheque', 'cheque-demo-anverso.png', 'image/png', 24, 'demo/cheques/anverso.png', 'demo-checksum-cheque-anverso'),
    (4011, 3001, 'cheque', 'cheque-demo-reverso.png', 'image/png', 24, 'demo/cheques/reverso.png', 'demo-checksum-cheque-reverso'),
    (4020, 3004, 'documento_consignacion', 'documentacion-origen-demo.pdf', 'application/pdf', 42, 'demo/consignaciones/origen.pdf', 'demo-checksum-origen'),
    (4030, 3004, 'documento_liquidacion', 'liquidacion-demo.pdf', 'application/pdf', 42, 'demo/documentos/liquidacion.pdf', 'demo-checksum-liquidacion'),
    (4101, 3004, 'consignacion', 'consignacion-demo-01.png', 'image/png', 24, 'demo/consignaciones/foto-01.png', 'demo-checksum-consignacion-01'),
    (4102, 3004, 'consignacion', 'consignacion-demo-02.png', 'image/png', 24, 'demo/consignaciones/foto-02.png', 'demo-checksum-consignacion-02'),
    (4103, 3004, 'consignacion', 'consignacion-demo-03.png', 'image/png', 24, 'demo/consignaciones/foto-03.png', 'demo-checksum-consignacion-03'),
    (4104, 3004, 'consignacion', 'consignacion-demo-04.png', 'image/png', 24, 'demo/consignaciones/foto-04.png', 'demo-checksum-consignacion-04'),
    (4105, 3004, 'consignacion', 'consignacion-demo-05.png', 'image/png', 24, 'demo/consignaciones/foto-05.png', 'demo-checksum-consignacion-05'),
    (4106, 3004, 'consignacion', 'consignacion-demo-06.png', 'image/png', 24, 'demo/consignaciones/foto-06.png', 'demo-checksum-consignacion-06');

INSERT INTO app_solicitudes_registro (
    id, email, nombre, apellido, domicilio_legal, id_pais_origen,
    foto_frente_dni_archivo_id, foto_dorso_dni_archivo_id, estado
) VALUES
    (3201, 'pendiente@quickbid.demo', 'Paula', 'Pendiente', 'Av. Demo 500', 32, 4001, 4002, 'pendiente_revision');

INSERT INTO app_medios_pago (
    id, cuenta_id, tipo, moneda, estado, principal, nacional, alias_visible,
    ultimos_4, titular, hash_identificador, limite_monto, consumo_actual,
    saldo_garantia, verificado_hasta, verificado_por_empleado_id
) VALUES
    (5001, 3001, 'tarjeta', 'ARS', 'verificado', true, true, 'Visa ARS terminada en 4242',
     '4242', 'Ana Aprobada', 'demo-token-card-ars-aprobado', 500000.00, 25100.00,
     NULL, CURRENT_TIMESTAMP + INTERVAL '7 days', 1003),
    (5002, 3001, 'cuenta_bancaria', 'USD', 'verificado', true, false, 'Cuenta USD demo',
     NULL, 'Ana Aprobada', 'demo-token-bank-usd-aprobado', 20000.00, 0.00,
     NULL, CURRENT_TIMESTAMP + INTERVAL '7 days', 1003),
    (5003, 3001, 'cheque_certificado', 'ARS', 'pendiente_verificacion', false, true, 'Cheque certificado pendiente',
     NULL, 'Ana Aprobada', 'demo-token-cheque-ars-pendiente', 150000.00, 0.00,
     150000.00, NULL, NULL),
    (5004, 3002, 'tarjeta', 'ARS', 'verificado', true, true, 'Mastercard ARS restringida',
     '4444', 'Bruno Restringido', 'demo-token-card-ars-multa', 250000.00, 150000.00,
     NULL, CURRENT_TIMESTAMP + INTERVAL '7 days', 1003),
    (5005, 3004, 'cuenta_bancaria', 'ARS', 'verificado', true, true, 'Cuenta ARS consignador',
     NULL, 'Diego Consignador', 'demo-token-bank-ars-consignador', 1000000.00, 0.00,
     NULL, CURRENT_TIMESTAMP + INTERVAL '7 days', 1003);

INSERT INTO app_tarjetas (medio_pago_id, marca, vencimiento_mes, vencimiento_anio) VALUES
    (5001, 'Visa', 12, 2030),
    (5004, 'Mastercard', 11, 2030);

INSERT INTO app_cuentas_bancarias (
    medio_pago_id, numero_cuenta_hash, cbu_cvu_hash, nombre_banco, alias
) VALUES
    (5002, 'demo-hash-account-usd', 'demo-hash-cbu-usd', 'Banco Demo Exterior', 'ANA.USD.DEMO'),
    (5005, 'demo-hash-account-consignor', 'demo-hash-cbu-consignor', 'Banco Demo Argentina', 'DIEGO.CONSIGNA');

INSERT INTO app_cheques_certificados (
    medio_pago_id, numero_cheque_hash, monto, fecha_vencimiento, banco_emisor,
    foto_anverso_archivo_id, foto_reverso_archivo_id
) VALUES
    (5003, 'demo-hash-cheque-001', 150000.00, CURRENT_DATE + 90, 'Banco Demo Argentina', 4010, 4011);

INSERT INTO app_direcciones_envio (
    id, cuenta_id, alias, destinatario, calle, numero, codigo_postal,
    localidad, provincia, pais, telefono, principal
) VALUES
    (5101, 3001, 'Casa', 'Ana Aprobada', 'Av. Demo', '100', 'C1000', 'Buenos Aires', 'Buenos Aires', 'Argentina', '+54 11 5555 0101', true);

INSERT INTO subastas (
    identificador, fecha, hora, estado, subastador, ubicacion,
    "capacidadAsistentes", "tieneDeposito", "seguridadPropia", categoria
) VALUES
    (
        6001,
        CURRENT_DATE + 11,
        TIME '09:30',
        'abierta',
        (SELECT identificador FROM personas WHERE documento = 'EMPRESA-SUB-001'),
        'Casa central QuickBid',
        100,
        'si',
        'si',
        'plata'
    ),
    (
        6002,
        CURRENT_DATE + 45,
        TIME '19:00',
        'abierta',
        (SELECT identificador FROM personas WHERE documento = 'EMPRESA-SUB-001'),
        'Casa central QuickBid',
        80,
        'si',
        'si',
        'especial'
    ),
    (
        6003,
        CURRENT_DATE + 20,
        TIME '17:00',
        'cerrada',
        (SELECT identificador FROM personas WHERE documento = 'EMPRESA-SUB-001'),
        'Casa central QuickBid',
        100,
        'si',
        'si',
        'comun'
    ),
    (
        6004,
        CURRENT_DATE + 60,
        TIME '20:00',
        'abierta',
        (SELECT identificador FROM personas WHERE documento = 'EMPRESA-SUB-001'),
        'Salón Oro QuickBid',
        50,
        'si',
        'si',
        'oro'
    );


INSERT INTO app_subasta_ext (
    subasta_id, titulo, descripcion, moneda, segmento, estado_operativo
) VALUES
    (6001, 'Diseño argentino en vivo', 'Subasta ARS con lote activo y puja vigente.', 'ARS', 'diseño', 'en_vivo'),
    (6002, 'Colección internacional USD', 'Subasta futura en dólares para probar moneda y preinscripción.', 'USD', 'colección', 'programada'),
    (6003, 'Remate clásico finalizado', 'Subasta cerrada con compras y multa demo.', 'ARS', 'general', 'finalizada'),
    (6004, 'Selección oro', 'Subasta oro para probar reglas de incremento flexible.', 'ARS', 'arte', 'abierta');

INSERT INTO productos (
    identificador, fecha, disponible, "descripcionCatalogo", "descripcionCompleta",
    revisor, duenio, seguro
) VALUES
    (8001, CURRENT_DATE, 'si', 'Sillón de diseño argentino', 'demo/docs/producto-8001.pdf', 1002, 2004, NULL),
    (8002, CURRENT_DATE, 'si', 'Juego de té de porcelana', 'demo/docs/producto-8002.pdf', 1002, 2004, NULL),
    (8003, CURRENT_DATE, 'si', 'Reloj internacional de colección', 'demo/docs/producto-8003.pdf', 1002, 2004, NULL),
    (8004, CURRENT_DATE, 'no', 'Lámpara clásica adjudicada con multa', 'demo/docs/producto-8004.pdf', 1002, 2004, NULL),
    (8005, CURRENT_DATE, 'no', 'Mesa restaurada vendida', 'demo/docs/producto-8005.pdf', 1002, 2004, NULL),
    (8006, CURRENT_DATE, 'si', 'Escultura con acuerdo aceptado', 'demo/docs/producto-8006.pdf', 1002, 2004, NULL),
    (8007, CURRENT_DATE, 'si', 'Obra para selección oro', 'demo/docs/producto-8007.pdf', 1002, 2004, NULL),
    (8008, CURRENT_DATE, 'no', 'Lote sin pujas adquirido por la empresa', 'demo/docs/producto-8008.pdf', 1002, 2004, NULL);

INSERT INTO fotos (identificador, producto, foto) VALUES
    (8101, 8001, decode('89504E470D0A1A0A', 'hex')),
    (8102, 8002, decode('89504E470D0A1A0A', 'hex')),
    (8103, 8003, decode('89504E470D0A1A0A', 'hex')),
    (8104, 8004, decode('89504E470D0A1A0A', 'hex')),
    (8105, 8005, decode('89504E470D0A1A0A', 'hex')),
    (8106, 8006, decode('89504E470D0A1A0A', 'hex')),
    (8107, 8007, decode('89504E470D0A1A0A', 'hex')),
    (8108, 8008, decode('89504E470D0A1A0A', 'hex'));

INSERT INTO catalogos (identificador, descripcion, subasta, responsable) VALUES
    (7001, 'Catálogo diseño argentino', 6001, 1004),
    (7002, 'Catálogo colección internacional', 6002, 1004),
    (7003, 'Catálogo remate clásico', 6003, 1004),
    (7004, 'Catálogo selección oro', 6004, 1004);

INSERT INTO "itemsCatalogo" (
    identificador, catalogo, producto, "precioBase", comision, subastado
) VALUES
    (9001, 7001, 8001, 20000.00, 2000.00, 'no'),
    (9002, 7001, 8002, 35000.00, 3500.00, 'no'),
    (9003, 7002, 8003, 1500.00, 150.00, 'no'),
    (9004, 7003, 8004, 120000.00, 12000.00, 'si'),
    (9005, 7003, 8005, 80000.00, 8000.00, 'si'),
    (9006, 7004, 8007, 250000.00, 25000.00, 'no'),
    (9007, 7003, 8008, 60000.00, 6000.00, 'si');

INSERT INTO app_subasta_estado_vivo (
    subasta_id, item_catalogo_activo_id, version, usuarios_conectados,
    lote_iniciado_at, lote_finaliza_estimado_at
) VALUES
    (6001, 9001, 1, 1, CURRENT_TIMESTAMP - INTERVAL '5 minutes', CURRENT_TIMESTAMP + INTERVAL '10 minutes'),
    (6002, NULL, 0, 0, NULL, NULL),
    (6003, NULL, 3, 0, NULL, NULL),
    (6004, NULL, 0, 0, NULL, NULL);

INSERT INTO app_inscripciones_subasta (
    id, subasta_id, cuenta_id, medio_pago_id, estado
) VALUES
    (10001, 6001, 3001, 5001, 'aprobada'),
    (10002, 6002, 3001, 5002, 'aprobada'),
    (10003, 6004, 3001, 5003, 'pendiente_validacion');

INSERT INTO asistentes (identificador, "numeroPostor", cliente, subasta) VALUES
    (11001, 1, 2001, 6001),
    (11002, 1, 2002, 6003),
    (11003, 2, 2001, 6003);

INSERT INTO pujos (identificador, asistente, item, importe, ganador) VALUES
    (12001, 11001, 9001, 25100.00, 'no'),
    (12002, 11002, 9004, 150000.00, 'si'),
    (12003, 11003, 9005, 95000.00, 'si');

INSERT INTO app_pujas_live (
    id, pujo_legacy_id, subasta_id, item_catalogo_id, cuenta_id, medio_pago_id,
    monto, moneda, estado, secuencia, version_estado, idempotency_key, confirmed_at
) VALUES
    (12501, 12001, 6001, 9001, 3001, 5001, 25100.00, 'ARS', 'aceptada', 1, 1, 'demo-bid-live-6001-1', CURRENT_TIMESTAMP),
    (12502, 12002, 6003, 9004, 3002, 5004, 150000.00, 'ARS', 'ganadora', 1, 1, 'demo-bid-winner-fine-6003-1', CURRENT_TIMESTAMP - INTERVAL '1 day'),
    (12503, 12003, 6003, 9005, 3001, 5001, 95000.00, 'ARS', 'ganadora', 1, 1, 'demo-bid-winner-paid-6003-2', CURRENT_TIMESTAMP - INTERVAL '1 day');

INSERT INTO app_compras (
    id, subasta_id, item_catalogo_id, producto_id, cuenta_comprador_id,
    comprador_empresa, puja_id, monto_adjudicacion, moneda, estado, medio_pago_id
) VALUES
    (13001, 6003, 9004, 8004, 3002, false, 12502, 150000.00, 'ARS', 'multa_activa', 5004),
    (13002, 6003, 9005, 8005, 3001, false, 12503, 95000.00, 'ARS', 'pagos_extra_pendientes', 5001),
    (13003, 6003, 9007, 8008, NULL, true, NULL, 60000.00, 'ARS', 'pagos_extra_pendientes', NULL);

INSERT INTO app_multas (
    id, cuenta_id, compra_id, monto, moneda, porcentaje, estado, vence_at
) VALUES
    (14001, 3002, 13001, 15000.00, 'ARS', 10.00, 'pendiente', CURRENT_TIMESTAMP + INTERVAL '72 hours');

INSERT INTO app_pagos (
    id, compra_id, multa_id, medio_pago_id, monto, moneda, estado,
    referencia_externa, idempotency_key, error_codigo, error_detalle
) VALUES
    (15001, 13001, NULL, 5004, 150000.00, 'ARS', 'rechazado',
     'DEMO-PAY-FAILED-001', 'demo-payment-failed-13001', 'INSUFFICIENT_FUNDS_OR_LIMIT', 'Falla simulada para generar multa.'),
    (15002, 13002, NULL, 5001, 95000.00, 'ARS', 'aprobado',
     'DEMO-PAY-OK-001', 'demo-payment-approved-13002', NULL, NULL);

INSERT INTO app_solicitudes_consignacion (
    id, cuenta_id, cliente_id, producto_id, item_catalogo_id, subasta_id,
    titulo, descripcion, categoria_sugerida, historia, artista_disenador,
    fecha_objeto, declaracion_propiedad, acepta_devolucion_con_cargo, estado,
    requiere_documentacion_origen, motivo_rechazo, revisor_empleado_id,
    valor_base_propuesto, moneda_propuesta, comision_comprador_pct,
    comision_vendedor_pct, acuerdo_texto, acuerdo_enviado_at,
    acuerdo_aceptado_at
) VALUES
    (16001, 3004, 2004, NULL, NULL, NULL,
     'Vasija pendiente de revisión', 'Solicitud recién enviada con seis fotos.', 'comun', NULL, NULL,
     'Década de 1980', true, true, 'pendiente_revision', false, NULL, NULL,
     NULL, NULL, NULL, NULL, NULL, NULL, NULL),
    (16002, 3004, 2004, NULL, NULL, NULL,
     'Cuadro con documentación adicional', 'La empresa solicitó documentación de origen.', 'especial', NULL, 'Artista Demo',
     '1995', true, true, 'documentacion_adicional', true, NULL, 1002,
     NULL, NULL, NULL, NULL, NULL, NULL, NULL),
    (16003, 3004, 2004, NULL, NULL, NULL,
     'Juego de copas en revisión física', 'Bien recibido y en inspección.', 'comun', NULL, NULL,
     '1960', true, true, 'revision_fisica', false, NULL, 1002,
     NULL, NULL, NULL, NULL, NULL, NULL, NULL),
    (16004, 3004, 2004, NULL, NULL, NULL,
     'Biblioteca con acuerdo pendiente', 'Revisión física aprobada; falta aceptación del acuerdo.', 'plata', NULL, NULL,
     '1930', true, true, 'acuerdo_pendiente', false, NULL, 1002,
     180000.00, 'ARS', 10.00, 10.00, 'Acuerdo demo pendiente de aceptación.', CURRENT_TIMESTAMP, NULL),
    (16005, 3004, 2004, 8002, 9002, 6001,
     'Juego de té publicado', 'Acuerdo aceptado y producto publicado.', 'plata', NULL, NULL,
     '1950', true, true, 'publicada', false, NULL, 1002,
     35000.00, 'ARS', 10.00, 10.00, 'Acuerdo demo aceptado.', CURRENT_TIMESTAMP - INTERVAL '10 days', CURRENT_TIMESTAMP - INTERVAL '9 days'),
    (16006, 3004, 2004, NULL, NULL, NULL,
     'Mueble pendiente de devolución', 'El acuerdo fue rechazado y corresponde devolución con cargo.', 'comun', NULL, NULL,
     '1970', true, true, 'devolucion_pendiente', false, 'Acuerdo rechazado por el consignador.', 1002,
     50000.00, 'ARS', 10.00, 10.00, 'Acuerdo demo rechazado.', CURRENT_TIMESTAMP - INTERVAL '3 days', NULL),
    (16007, 3004, 2004, 8005, 9005, 6003,
     'Mesa restaurada vendida', 'Bien vendido y liquidado al consignador.', 'comun', NULL, NULL,
     '1940', true, true, 'liquidada', false, NULL, 1002,
     80000.00, 'ARS', 10.00, 10.00, 'Acuerdo demo liquidado.', CURRENT_TIMESTAMP - INTERVAL '20 days', CURRENT_TIMESTAMP - INTERVAL '19 days'),
    (16008, 3004, 2004, 8006, NULL, NULL,
     'Escultura con acuerdo aceptado', 'Producto legacy creado luego de aceptar el acuerdo; pendiente de publicación.', 'oro', NULL, 'Escultor Demo',
     '2005', true, true, 'acuerdo_aceptado', false, NULL, 1002,
     300000.00, 'ARS', 10.00, 10.00, 'Acuerdo demo aceptado, aún no publicado.', CURRENT_TIMESTAMP - INTERVAL '2 days', CURRENT_TIMESTAMP - INTERVAL '1 day');

INSERT INTO app_consignacion_fotos (id, solicitud_id, archivo_id, orden) VALUES
    (17001, 16001, 4101, 1),
    (17002, 16001, 4102, 2),
    (17003, 16001, 4103, 3),
    (17004, 16001, 4104, 4),
    (17005, 16001, 4105, 5),
    (17006, 16001, 4106, 6);

INSERT INTO app_consignacion_documentos_origen (
    id, solicitud_id, archivo_id, estado
) VALUES
    (17101, 16002, 4020, 'pendiente');

INSERT INTO app_consignacion_devoluciones (
    id, solicitud_id, motivo, costo, moneda, estado
) VALUES
    (17201, 16006, 'Acuerdo rechazado por el consignador.', 12000.00, 'ARS', 'pendiente_pago');

INSERT INTO app_liquidaciones_consignacion (
    id, solicitud_id, compra_id, monto_bruto, comision, monto_neto,
    cuenta_destino, estado, paid_at
) VALUES
    (17301, 16007, 13002, 95000.00, 9500.00, 85500.00,
     'Cuenta bancaria demo terminada en consignador', 'pagada', CURRENT_TIMESTAMP - INTERVAL '12 hours');

INSERT INTO app_documentos (
    id, tipo, referencia_tipo, referencia_id, archivo_id, estado
) VALUES
    (17401, 'liquidacion_venta', 'consignacion', 16007, 4030, 'disponible');

INSERT INTO app_notificaciones (
    id, cuenta_id, tipo, titulo, descripcion, referencia_tipo, referencia_id, leida
) VALUES
    (18001, 3001, 'inscripcion_subasta', 'Inscripción aprobada', 'Tu inscripción a la subasta ARS fue aprobada.', 'subasta', 6001, false),
    (18002, 3001, 'puja_aceptada', 'Puja aceptada', 'Tu puja demo quedó como mejor oferta actual.', 'puja', 12501, false),
    (18003, 3002, 'multa_generada', 'Multa pendiente', 'El cobro automático falló y se generó una multa.', 'multa', 14001, false),
    (18004, 3004, 'acuerdo_disponible', 'Acuerdo disponible', 'Hay un acuerdo de consignación pendiente de aceptación.', 'consignacion', 16004, false),
    (18005, 3004, 'liquidacion_disponible', 'Liquidación disponible', 'La liquidación demo ya está disponible.', 'consignacion', 16007, true);

INSERT INTO app_auditoria (
    id, actor_tipo, actor_id, accion, entidad_tipo, entidad_id, metadata_json
) VALUES
    (19001, 'sistema', NULL, 'SEED_DEMO_CREADO', 'demo', NULL, '{"migration":"V4__seed_demo_data.sql"}'),
    (19002, 'sistema', NULL, 'MULTA_GENERADA', 'app_multas', 14001, '{"scenario":"demo"}'),
    (19003, 'sistema', NULL, 'CONSIGNACION_LIQUIDADA', 'app_solicitudes_consignacion', 16007, '{"scenario":"demo"}');

SELECT setval(pg_get_serial_sequence('personas', 'identificador'), (SELECT max(identificador) FROM personas), true);
SELECT setval(pg_get_serial_sequence('sectores', 'identificador'), (SELECT max(identificador) FROM sectores), true);
SELECT setval(pg_get_serial_sequence('subastas', 'identificador'), (SELECT max(identificador) FROM subastas), true);
SELECT setval(pg_get_serial_sequence('productos', 'identificador'), (SELECT max(identificador) FROM productos), true);
SELECT setval(pg_get_serial_sequence('fotos', 'identificador'), (SELECT max(identificador) FROM fotos), true);
SELECT setval(pg_get_serial_sequence('catalogos', 'identificador'), (SELECT max(identificador) FROM catalogos), true);
SELECT setval(pg_get_serial_sequence('"itemsCatalogo"', 'identificador'), (SELECT max(identificador) FROM "itemsCatalogo"), true);
SELECT setval(pg_get_serial_sequence('asistentes', 'identificador'), (SELECT max(identificador) FROM asistentes), true);
SELECT setval(pg_get_serial_sequence('pujos', 'identificador'), (SELECT max(identificador) FROM pujos), true);
SELECT setval(pg_get_serial_sequence('app_cuentas', 'id'), (SELECT max(id) FROM app_cuentas), true);
SELECT setval(pg_get_serial_sequence('app_archivos', 'id'), (SELECT max(id) FROM app_archivos), true);
SELECT setval(pg_get_serial_sequence('app_solicitudes_registro', 'id'), (SELECT max(id) FROM app_solicitudes_registro), true);
SELECT setval(pg_get_serial_sequence('app_medios_pago', 'id'), (SELECT max(id) FROM app_medios_pago), true);
SELECT setval(pg_get_serial_sequence('app_direcciones_envio', 'id'), (SELECT max(id) FROM app_direcciones_envio), true);
SELECT setval(pg_get_serial_sequence('app_inscripciones_subasta', 'id'), (SELECT max(id) FROM app_inscripciones_subasta), true);
SELECT setval(pg_get_serial_sequence('app_pujas_live', 'id'), (SELECT max(id) FROM app_pujas_live), true);
SELECT setval(pg_get_serial_sequence('app_compras', 'id'), (SELECT max(id) FROM app_compras), true);
SELECT setval(pg_get_serial_sequence('app_multas', 'id'), (SELECT max(id) FROM app_multas), true);
SELECT setval(pg_get_serial_sequence('app_pagos', 'id'), (SELECT max(id) FROM app_pagos), true);
SELECT setval(pg_get_serial_sequence('app_solicitudes_consignacion', 'id'), (SELECT max(id) FROM app_solicitudes_consignacion), true);
SELECT setval(pg_get_serial_sequence('app_consignacion_fotos', 'id'), (SELECT max(id) FROM app_consignacion_fotos), true);
SELECT setval(pg_get_serial_sequence('app_consignacion_documentos_origen', 'id'), (SELECT max(id) FROM app_consignacion_documentos_origen), true);
SELECT setval(pg_get_serial_sequence('app_consignacion_devoluciones', 'id'), (SELECT max(id) FROM app_consignacion_devoluciones), true);
SELECT setval(pg_get_serial_sequence('app_liquidaciones_consignacion', 'id'), (SELECT max(id) FROM app_liquidaciones_consignacion), true);
SELECT setval(pg_get_serial_sequence('app_documentos', 'id'), (SELECT max(id) FROM app_documentos), true);
SELECT setval(pg_get_serial_sequence('app_notificaciones', 'id'), (SELECT max(id) FROM app_notificaciones), true);
SELECT setval(pg_get_serial_sequence('app_auditoria', 'id'), (SELECT max(id) FROM app_auditoria), true);
