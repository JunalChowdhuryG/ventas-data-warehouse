 
-- VentasDW | V8: soporte de la Fase 3 (ETL)
 

ALTER TABLE dwh.dim_producto ADD COLUMN precio_lista numeric(12,2);
COMMENT ON COLUMN dwh.dim_producto.precio_lista IS 'Precio de lista (SCD tipo 1: no genera nuevas versiones). Referencia para imputar y detectar atípicos.';

--   staging de maestros
CREATE UNLOGGED TABLE stg.producto (
    producto_id     integer       PRIMARY KEY,
    producto        varchar(200)  NOT NULL,
    categoria       varchar(100)  NOT NULL,
    precio_lista    numeric(12,2),
    updated_at      timestamptz   NOT NULL,
    hash_atributos  char(32) GENERATED ALWAYS AS (md5(producto || '|' || categoria)) STORED
);

CREATE UNLOGGED TABLE stg.cliente (
    cliente_id      integer       PRIMARY KEY,
    cliente         varchar(200)  NOT NULL,
    email           varchar(254),
    updated_at      timestamptz   NOT NULL,
    hash_atributos  char(32) GENERATED ALWAYS AS (md5(cliente || '|' || coalesce(email, ''))) STORED
);

CREATE UNLOGGED TABLE stg.ciudad (
    ciudad_id     integer      PRIMARY KEY,
    pais          varchar(80)  NOT NULL,
    departamento  varchar(80)  NOT NULL,
    provincia     varchar(80)  NOT NULL,
    ciudad        varchar(80)  NOT NULL
);

CREATE UNLOGGED TABLE stg.empleado (
    empleado_id  integer       PRIMARY KEY,
    empleado     varchar(200)  NOT NULL,
    cargo        varchar(100)
);

--   stg.venta_linea
ALTER TABLE stg.venta_linea ADD COLUMN precio_lista numeric(12,2);   -- resuelto al transformar
ALTER TABLE stg.venta_linea ADD COLUMN corregido    boolean NOT NULL DEFAULT false;
ALTER TABLE stg.venta_linea DROP CONSTRAINT venta_linea_estado_validacion_check;
ALTER TABLE stg.venta_linea ADD CONSTRAINT venta_linea_estado_validacion_check
    CHECK (estado_validacion IN ('PENDIENTE', 'VALIDO', 'CORREGIDO', 'RECHAZADO', 'EXCLUIDO'));
COMMENT ON COLUMN stg.venta_linea.estado_validacion IS
    'PENDIENTE: sin evaluar. VALIDO/CORREGIDO: se carga. RECHAZADO: no se carga (error o duplicado). EXCLUIDO: pedido cancelado o devuelto (se elimina del DWH si estaba).';

--   archivos CSV procesados
CREATE TABLE etl.archivo_cargado (
    nombre        varchar(200) PRIMARY KEY,
    filas         integer      NOT NULL,
    ejecucion_id  bigint       NOT NULL REFERENCES etl.ejecucion (id),
    cargado_en    timestamptz  NOT NULL DEFAULT now()
);
COMMENT ON TABLE etl.archivo_cargado IS 'CSV del canal online ya procesados. Un archivo nuevo = carga pendiente.';
 
CREATE INDEX ix_excepcion_clave ON etl.excepcion (clave_natural, regla);

--   permisos
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA stg TO dwh_etl_rw;
GRANT SELECT, INSERT, UPDATE, DELETE ON etl.archivo_cargado TO dwh_etl_rw;
GRANT TRUNCATE ON dwh.fact_ventas TO dwh_etl_rw;                      -- carga total
GRANT DELETE ON dwh.dim_producto, dwh.dim_cliente, dwh.dim_geografia, dwh.dim_empleado TO dwh_etl_rw;  -- carga total
GRANT SELECT ON ALL TABLES IN SCHEMA etl TO dwh_api_ro;               -- la API puede mostrar el estado del ETL
GRANT USAGE ON SCHEMA etl TO dwh_api_ro;
ALTER DEFAULT PRIVILEGES IN SCHEMA stg GRANT ALL PRIVILEGES ON TABLES TO dwh_etl_rw;
ALTER DEFAULT PRIVILEGES IN SCHEMA etl GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO dwh_etl_rw;
