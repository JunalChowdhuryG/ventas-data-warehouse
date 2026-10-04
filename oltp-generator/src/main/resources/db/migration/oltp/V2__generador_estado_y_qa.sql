

CREATE TABLE generador_estado (
    clave  varchar(60) PRIMARY KEY,
    valor  bigint      NOT NULL
);
INSERT INTO generador_estado (clave, valor) VALUES ('pedido_online', 1);

CREATE TABLE qa_error_inyectado (
    id               bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fuente           varchar(20)  NOT NULL CHECK (fuente IN ('OLTP', 'CSV_ONLINE')),
    tipo             varchar(40)  NOT NULL,
    pedido_id        bigint       NOT NULL,        
    linea            integer,                     
    fecha_pedido     date,
    detalle          text,
    creado_en        timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_qa_error_tipo   ON qa_error_inyectado (fuente, tipo);
CREATE INDEX ix_qa_error_pedido ON qa_error_inyectado (fuente, pedido_id);

COMMENT ON TABLE qa_error_inyectado IS 'Errores inyectados a proposito por el generador (pruebas de calidad del ETL). No es parte del modelo de negocio.';
