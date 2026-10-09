--  
-- VentasDW | V6: vistas 

-- Vistas de la versión vigente de las dimensiones SCD2
CREATE VIEW dwh.v_dim_producto_actual AS
    SELECT producto_key, producto_id, producto, categoria FROM dwh.dim_producto WHERE es_actual;
CREATE VIEW dwh.v_dim_cliente_actual AS
    SELECT cliente_key, cliente_id, cliente, email FROM dwh.dim_cliente WHERE es_actual;

-- Agregado 1: ventas por mes y categoría  
CREATE MATERIALIZED VIEW dwh.mv_ventas_mensual_categoria AS
SELECT d.anio,
       d.mes,
       p.categoria,
       SUM(f.importe_total)                            AS ventas,
       SUM(f.cantidad)                                 AS unidades,
       COUNT(DISTINCT (f.canal_key, f.pedido_id))      AS pedidos
FROM dwh.fact_ventas f
JOIN dwh.dim_fecha    d ON d.fecha_key    = f.fecha_key
JOIN dwh.dim_producto p ON p.producto_key = f.producto_key
GROUP BY d.anio, d.mes, p.categoria
WITH DATA;
CREATE UNIQUE INDEX ux_mv_ventas_mensual_categoria ON dwh.mv_ventas_mensual_categoria (anio, mes, categoria);

-- Agregado 2: ventas por mes, departamento y canal
CREATE MATERIALIZED VIEW dwh.mv_ventas_mensual_departamento AS
SELECT d.anio,
       d.mes,
       g.departamento,
       c.canal,
       SUM(f.importe_total)                            AS ventas,
       SUM(f.cantidad)                                 AS unidades,
       COUNT(DISTINCT (f.canal_key, f.pedido_id))      AS pedidos
FROM dwh.fact_ventas f
JOIN dwh.dim_fecha     d ON d.fecha_key     = f.fecha_key
JOIN dwh.dim_geografia g ON g.geografia_key = f.geografia_key
JOIN dwh.dim_canal     c ON c.canal_key     = f.canal_key
GROUP BY d.anio, d.mes, g.departamento, c.canal
WITH DATA;
CREATE UNIQUE INDEX ux_mv_ventas_mensual_depto ON dwh.mv_ventas_mensual_departamento (anio, mes, departamento, canal);
 
CREATE OR REPLACE FUNCTION dwh.refrescar_agregados()
RETURNS void
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = dwh, pg_temp
AS $$
BEGIN
    REFRESH MATERIALIZED VIEW CONCURRENTLY dwh.mv_ventas_mensual_categoria;
    REFRESH MATERIALIZED VIEW CONCURRENTLY dwh.mv_ventas_mensual_departamento;
END;
$$;
