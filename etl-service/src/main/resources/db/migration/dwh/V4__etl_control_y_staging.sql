--  
-- VentasDW | V4: control del ETL (schema etl) y staging (schema stg)
CREATE TABLE etl.control (
    fuente                  varchar(30)  NOT NULL,              -- OLTP, CSV_ONLINE
    tabla                   varchar(60)  NOT NULL,
    watermark               timestamptz  NOT NULL DEFAULT TIMESTAMPTZ '1970-01-01 00:00:00+00',
    carga_inicial_completa  boolean      NOT NULL DEFAULT false,
    ultima_ejecucion        timestamptz,
    PRIMARY KEY (fuente, tabla)
);
COMMENT ON TABLE etl.control IS 'Marca de agua por tabla fuente. Solo se avanza si la ejecución termina bien.';

--   etl.ejecucion
CREATE TABLE etl.ejecucion (
    id                bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job               varchar(60)  NOT NULL,
    tipo_carga        varchar(12)  NOT NULL CHECK (tipo_carga IN ('INICIAL', 'INCREMENTAL', 'TOTAL')),
    estado            varchar(12)  NOT NULL DEFAULT 'EN_CURSO' CHECK (estado IN ('EN_CURSO', 'EXITOSA', 'FALLIDA')),
    inicio            timestamptz  NOT NULL DEFAULT now(),
    fin               timestamptz,
    filas_extraidas   bigint       NOT NULL DEFAULT 0,
    filas_cargadas    bigint       NOT NULL DEFAULT 0,
    filas_rechazadas  bigint       NOT NULL DEFAULT 0,
    mensaje           text,
    CONSTRAINT ck_ejecucion_fin CHECK (fin IS NULL OR fin >= inicio)
);
CREATE INDEX ix_ejecucion_job_inicio ON etl.ejecucion (job, inicio DESC);
COMMENT ON TABLE etl.ejecucion IS 'Historial de ejecuciones del ETL. Base de las métricas de Prometheus.';

--   etl.excepcion
CREATE TABLE etl.excepcion (
    id             bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ejecucion_id   bigint       NOT NULL REFERENCES etl.ejecucion (id) ON DELETE CASCADE,
    tabla          varchar(60)  NOT NULL,
    clave_natural  varchar(120),
    regla          varchar(80)  NOT NULL,
    severidad      varchar(12)  NOT NULL CHECK (severidad IN ('ERROR', 'ADVERTENCIA', 'CORREGIDO')),
    detalle        jsonb,
    registrada_en  timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_excepcion_ejecucion ON etl.excepcion (ejecucion_id);
CREATE INDEX ix_excepcion_regla     ON etl.excepcion (regla, registrada_en DESC);
COMMENT ON TABLE etl.excepcion IS 'Registros rechazados, corregidos o con advertencia, con la regla de calidad que los afectó.';

--   stg.venta_linea 
CREATE UNLOGGED TABLE stg.venta_linea (
    stg_id              bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ejecucion_id        bigint       NOT NULL,
    fuente              varchar(30)  NOT NULL,                  
    canal_codigo        varchar(20),
    pedido_id           bigint,
    linea               integer,
    fecha_pedido        date,
    estado_origen       varchar(40),                             
    estado              varchar(20),                            
    cliente_id          integer,
    cliente             varchar(200),                           
    email               varchar(254),
    ciudad_id           integer,
    pais                varchar(80),
    departamento        varchar(80),
    provincia           varchar(80),
    ciudad              varchar(80),
    empleado_id         integer,
    empleado            varchar(200),
    cargo               varchar(100),
    producto_id         integer,
    producto            varchar(200),
    categoria           varchar(100),
    cantidad            integer,
    precio_unitario     numeric(14,4),
    descuento           numeric(7,4),
    moneda              char(3),                                 
    updated_at          timestamptz,
    estado_validacion   varchar(12)  NOT NULL DEFAULT 'PENDIENTE'
                        CHECK (estado_validacion IN ('PENDIENTE', 'VALIDO', 'CORREGIDO', 'RECHAZADO'))
);
CREATE INDEX ix_stg_venta_linea_ejec ON stg.venta_linea (ejecucion_id, estado_validacion);
COMMENT ON TABLE stg.venta_linea IS 'Staging unificado de líneas de venta (OLTP y CSV online). Se trunca o se filtra por ejecucion_id.';
