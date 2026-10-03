
\set ON_ERROR_STOP on
BEGIN;

-- 1) Dimensiones de ejemplo
INSERT INTO dwh.dim_geografia (ciudad_id, pais, departamento, provincia, ciudad) VALUES
  (1, 'Peru', 'Lima',   'Lima',   'Lima'),
  (2, 'Peru', 'Cusco',  'Cusco',  'Cusco');
INSERT INTO dwh.dim_empleado (empleado_id, empleado, cargo) VALUES (10, 'Ana Torres', 'Vendedora');
INSERT INTO dwh.dim_cliente (cliente_id, cliente, email, hash_atributos, vigente_desde)
  VALUES (100, 'Carlos Ruiz', 'carlos@correo.pe', md5('Carlos Ruiz|carlos@correo.pe'), DATE '1900-01-01');

-- 2) SCD2 en producto: version 1 (categoria Snacks) y luego cambio a Golosinas el 2026-03-01
INSERT INTO dwh.dim_producto (producto_id, producto, categoria, hash_atributos, vigente_desde)
  VALUES (500, 'Chocolate 100 g', 'Snacks', md5('Chocolate 100 g|Snacks'), DATE '1900-01-01');
UPDATE dwh.dim_producto SET vigente_hasta = DATE '2026-03-01', es_actual = false
 WHERE producto_id = 500 AND es_actual;
INSERT INTO dwh.dim_producto (producto_id, producto, categoria, hash_atributos, vigente_desde)
  VALUES (500, 'Chocolate 100 g', 'Golosinas', md5('Chocolate 100 g|Golosinas'), DATE '2026-03-01');

-- 3) Hechos: una venta en febrero (version antigua) y dos en marzo (version nueva)
INSERT INTO dwh.fact_ventas (fecha_key, canal_key, producto_key, cliente_key, geografia_key, empleado_key,
                             pedido_id, linea, cantidad, precio_unitario, descuento)
SELECT 20260215, 1, p.producto_key, c.cliente_key, g.geografia_key, e.empleado_key, 1, 1, 3, 10.00, 0.10
FROM dwh.dim_producto p, dwh.dim_cliente c, dwh.dim_geografia g, dwh.dim_empleado e
WHERE p.producto_id = 500 AND p.categoria = 'Snacks' AND c.cliente_id = 100 AND g.ciudad_id = 1 AND e.empleado_id = 10;

INSERT INTO dwh.fact_ventas (fecha_key, canal_key, producto_key, cliente_key, geografia_key, empleado_key,
                             pedido_id, linea, cantidad, precio_unitario, descuento)
SELECT 20260310, 2, p.producto_key, c.cliente_key, g.geografia_key, -1, v.pedido, 1, v.cant, 10.00, 0
FROM dwh.dim_producto p, dwh.dim_cliente c, dwh.dim_geografia g,
     (VALUES (2, 4), (3, 6)) AS v(pedido, cant)
WHERE p.producto_id = 500 AND p.es_actual AND c.cliente_id = 100 AND g.ciudad_id = 2;

-- 4) La columna generada calcula el importe: 3 * 10 * (1 - 0.10) = 27.00
\echo '--- importe_total generado (esperado: 27.00, 40.00, 60.00)'
SELECT pedido_id, importe_total FROM dwh.fact_ventas ORDER BY fecha_key, pedido_id;

-- 5) ROLLUP por anio y trimestre/mes, con la categoria vigente en cada momento
\echo '--- ventas por mes y categoria (esperado: feb Snacks 27.00; mar Golosinas 100.00)'
SELECT d.mes, p.categoria, SUM(f.importe_total) AS ventas
FROM dwh.fact_ventas f
JOIN dwh.dim_fecha d ON d.fecha_key = f.fecha_key
JOIN dwh.dim_producto p ON p.producto_key = f.producto_key
GROUP BY ROLLUP (d.mes, p.categoria)
ORDER BY d.mes, p.categoria;

-- 6) Particion correcta: las filas de 2026 deben estar en fact_ventas_2026 y no en la default
\echo '--- filas por particion (esperado: fact_ventas_2026 = 3, default = 0)'
SELECT tableoid::regclass AS particion, count(*) FROM dwh.fact_ventas GROUP BY 1 ORDER BY 1;

-- 7) Agregados materializados
REFRESH MATERIALIZED VIEW dwh.mv_ventas_mensual_categoria;
\echo '--- mv_ventas_mensual_categoria'
SELECT * FROM dwh.mv_ventas_mensual_categoria ORDER BY anio, mes;

-- 8) Restricciones: cada intento siguiente DEBE fallar. Se prueba con subtransacciones.
DO $$
DECLARE
  v_fallos int := 0;
BEGIN
  -- 8a) Versiones SCD2 solapadas del mismo producto
  BEGIN
    INSERT INTO dwh.dim_producto (producto_id, producto, categoria, hash_atributos, vigente_desde, vigente_hasta, es_actual)
    VALUES (500, 'Chocolate 100 g', 'Otra', md5('x'), DATE '2026-02-01', DATE '2026-04-01', false);
  EXCEPTION WHEN exclusion_violation THEN v_fallos := v_fallos + 1; END;

  -- 8b) Cantidad no positiva
  BEGIN
    INSERT INTO dwh.fact_ventas (fecha_key, canal_key, producto_key, cliente_key, geografia_key, empleado_key,
                                 pedido_id, linea, cantidad, precio_unitario)
    VALUES (20260310, 1, -1, -1, -1, -1, 99, 1, 0, 5);
  EXCEPTION WHEN check_violation THEN v_fallos := v_fallos + 1; END;

  -- 8c) Hecho duplicado (misma clave de linea)
  BEGIN
    INSERT INTO dwh.fact_ventas (fecha_key, canal_key, producto_key, cliente_key, geografia_key, empleado_key,
                                 pedido_id, linea, cantidad, precio_unitario)
    VALUES (20260215, 1, -1, -1, -1, -1, 1, 1, 1, 5);
  EXCEPTION WHEN unique_violation THEN v_fallos := v_fallos + 1; END;

  -- 8d) Fecha inexistente en dim_fecha
  BEGIN
    INSERT INTO dwh.fact_ventas (fecha_key, canal_key, producto_key, cliente_key, geografia_key, empleado_key,
                                 pedido_id, linea, cantidad, precio_unitario)
    VALUES (19990101, 1, -1, -1, -1, -1, 98, 1, 1, 5);
  EXCEPTION WHEN foreign_key_violation THEN v_fallos := v_fallos + 1; END;

  RAISE NOTICE 'Restricciones que bloquearon datos invalidos: % de 4 (esperado: 4)', v_fallos;
  IF v_fallos <> 4 THEN RAISE EXCEPTION 'Alguna restriccion no se aplico'; END IF;
END
$$;

-- 9) Idempotencia de la carga: el upsert por clave de linea no duplica
INSERT INTO dwh.fact_ventas (fecha_key, canal_key, producto_key, cliente_key, geografia_key, empleado_key,
                             pedido_id, linea, cantidad, precio_unitario, descuento)
SELECT fecha_key, canal_key, producto_key, cliente_key, geografia_key, empleado_key, pedido_id, linea, 5, 10.00, 0
FROM dwh.fact_ventas WHERE pedido_id = 1
ON CONFLICT (fecha_key, canal_key, pedido_id, linea)
DO UPDATE SET cantidad = EXCLUDED.cantidad, precio_unitario = EXCLUDED.precio_unitario,
              descuento = EXCLUDED.descuento, cargado_en = now();
\echo '--- tras el upsert (esperado: 3 filas; pedido 1 con importe 50.00)'
SELECT count(*) AS filas, max(importe_total) FILTER (WHERE pedido_id = 1) AS importe_pedido_1 FROM dwh.fact_ventas;

-- 10) Poda de particiones: la consulta de 2026 solo debe leer fact_ventas_2026
\echo '--- plan (debe mencionar solo fact_ventas_2026)'
EXPLAIN (COSTS OFF) SELECT sum(importe_total) FROM dwh.fact_ventas WHERE fecha_key BETWEEN 20260101 AND 20261231;

ROLLBACK;
