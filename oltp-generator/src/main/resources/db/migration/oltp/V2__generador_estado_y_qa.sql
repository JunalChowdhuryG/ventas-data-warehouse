-- =====================================================================
-- VentasDW | OLTP V2: soporte del generador de datos
--  * generador_estado: contadores del generador (siguiente id de pedido online).
--  * qa_error_inyectado: "hoja de respuestas" de los errores que el generador inyecta a propósito.
--    No es parte del modelo de negocio: sirve para comprobar que las reglas de calidad del ETL
--    detectan todo lo que deberían (compara contra etl.excepcion).
-- =====================================================================

CREATE TABLE generador_estado (
    clave  varchar(60) PRIMARY KEY,
    valor  bigint      NOT NULL
);
INSERT INTO generador_estado (clave, valor) VALUES ('pedido_online', 1);

CREATE TABLE qa_error_inyectado (
    id               bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fuente           varchar(20)  NOT NULL CHECK (fuente IN ('OLTP', 'CSV_ONLINE')),
    tipo             varchar(40)  NOT NULL,
    pedido_id        bigint       NOT NULL,        -- id de pedido en su fuente (el de CSV puede coincidir con uno del OLTP)
    linea            integer,                      -- null cuando el error es del pedido completo
    fecha_pedido     date,
    detalle          text,
    creado_en        timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_qa_error_tipo   ON qa_error_inyectado (fuente, tipo);
CREATE INDEX ix_qa_error_pedido ON qa_error_inyectado (fuente, pedido_id);

COMMENT ON TABLE qa_error_inyectado IS 'Errores inyectados a propósito por el generador (pruebas de calidad del ETL). No es parte del modelo de negocio.';
