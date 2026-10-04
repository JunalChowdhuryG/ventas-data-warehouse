 
-- VentasDW | V7: permisos por minimo privilegio 

GRANT USAGE ON SCHEMA stg, dwh, etl TO dwh_etl_rw;
GRANT USAGE ON SCHEMA dwh TO dwh_api_ro;

-- ETL: staging completo, control completo, dimensiones y hechos con escritura
GRANT ALL PRIVILEGES ON ALL TABLES    IN SCHEMA stg TO dwh_etl_rw;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA stg TO dwh_etl_rw;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA etl TO dwh_etl_rw;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA etl TO dwh_etl_rw;

GRANT SELECT, INSERT, UPDATE ON
    dwh.dim_producto, dwh.dim_cliente, dwh.dim_geografia, dwh.dim_empleado, dwh.fact_ventas
    TO dwh_etl_rw;
GRANT DELETE ON dwh.fact_ventas TO dwh_etl_rw;            -- necesario para la carga total
GRANT SELECT ON dwh.dim_fecha, dwh.dim_canal TO dwh_etl_rw;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA dwh TO dwh_etl_rw;
GRANT SELECT ON ALL TABLES IN SCHEMA dwh TO dwh_etl_rw;
GRANT EXECUTE ON FUNCTION dwh.refrescar_agregados()          TO dwh_etl_rw;
GRANT EXECUTE ON FUNCTION dwh.crear_particion_anual(integer) TO dwh_etl_rw;

-- API y Grafana: solo lectura del DWH
GRANT SELECT ON ALL TABLES IN SCHEMA dwh TO dwh_api_ro;

-- Objetos que se creen mas adelante en dwh heredan lectura para la API
ALTER DEFAULT PRIVILEGES IN SCHEMA dwh GRANT SELECT ON TABLES TO dwh_api_ro;
ALTER DEFAULT PRIVILEGES IN SCHEMA dwh GRANT SELECT ON TABLES TO dwh_etl_rw;
