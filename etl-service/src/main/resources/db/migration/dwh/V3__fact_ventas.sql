 
-- VentasDW | V3: tabla de hechos particionada 

CREATE TABLE dwh.fact_ventas (
    -- Claves de dimensiones
    fecha_key        integer       NOT NULL REFERENCES dwh.dim_fecha (fecha_key),
    canal_key        smallint      NOT NULL REFERENCES dwh.dim_canal (canal_key),
    producto_key     integer       NOT NULL REFERENCES dwh.dim_producto (producto_key),
    cliente_key      integer       NOT NULL REFERENCES dwh.dim_cliente (cliente_key),
    geografia_key    integer       NOT NULL REFERENCES dwh.dim_geografia (geografia_key),
    empleado_key     integer       NOT NULL REFERENCES dwh.dim_empleado (empleado_key), 
    pedido_id        bigint        NOT NULL,
    linea            integer       NOT NULL,
    -- Medidas
    cantidad         integer       NOT NULL CHECK (cantidad > 0),
    precio_unitario  numeric(12,2) NOT NULL CHECK (precio_unitario >= 0),
    descuento        numeric(5,4)  NOT NULL DEFAULT 0 CHECK (descuento >= 0 AND descuento <= 1),
    importe_total    numeric(14,2) GENERATED ALWAYS AS
                         (round(cantidad * precio_unitario * (1 - descuento), 2)) STORED,
 
    ejecucion_id     bigint,                                     
    cargado_en       timestamptz   NOT NULL DEFAULT now(), 
    PRIMARY KEY (fecha_key, canal_key, pedido_id, linea)
) PARTITION BY RANGE (fecha_key);

COMMENT ON TABLE  dwh.fact_ventas IS 'Hechos de ventas. Una fila por línea de pedido. Particionada por año sobre fecha_key.';
COMMENT ON COLUMN dwh.fact_ventas.importe_total IS 'Medida calculada: cantidad * precio_unitario * (1 - descuento). Columna generada, no se inserta.';
 
CREATE INDEX ix_fact_ventas_producto  ON dwh.fact_ventas (producto_key);
CREATE INDEX ix_fact_ventas_cliente   ON dwh.fact_ventas (cliente_key);
CREATE INDEX ix_fact_ventas_geografia ON dwh.fact_ventas (geografia_key);
CREATE INDEX ix_fact_ventas_empleado  ON dwh.fact_ventas (empleado_key);
CREATE INDEX ix_fact_ventas_canal     ON dwh.fact_ventas (canal_key);
 
CREATE OR REPLACE FUNCTION dwh.crear_particion_anual(p_anio integer)
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    EXECUTE format(
        'CREATE TABLE IF NOT EXISTS dwh.%I PARTITION OF dwh.fact_ventas FOR VALUES FROM (%s) TO (%s)',
        'fact_ventas_' || p_anio,
        p_anio * 10000 + 101,
        (p_anio + 1) * 10000 + 101);
END;
$$;
 
SELECT dwh.crear_particion_anual(y) FROM generate_series(2024, 2030) AS y;
CREATE TABLE dwh.fact_ventas_default PARTITION OF dwh.fact_ventas DEFAULT;
COMMENT ON TABLE dwh.fact_ventas_default IS 'Filas fuera de las particiones anuales. Debe estar vacía: si recibe datos, crear la partición del año faltante.';
