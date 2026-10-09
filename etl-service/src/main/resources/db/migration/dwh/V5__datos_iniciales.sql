--  
-- VentasDW | V5: datos iniciales (dim_fecha, dim_canal, miembros Desconocido, control)
--  

--   dim_fecha
-- Calendario 2020-01-01 a 2035-12-31
INSERT INTO dwh.dim_fecha (fecha_key, fecha, anio, trimestre, mes, nombre_mes, anio_mes,
                           dia, dia_semana, nombre_dia_semana, es_fin_de_semana)
SELECT to_char(d, 'YYYYMMDD')::integer,
       d::date,
       extract(year    FROM d)::smallint,
       extract(quarter FROM d)::smallint,
       extract(month   FROM d)::smallint,
       (ARRAY['Enero','Febrero','Marzo','Abril','Mayo','Junio','Julio','Agosto',
              'Septiembre','Octubre','Noviembre','Diciembre'])[extract(month FROM d)::int],
       to_char(d, 'YYYYMM')::integer,
       extract(day     FROM d)::smallint,
       extract(isodow  FROM d)::smallint,
       (ARRAY['Lunes','Martes','Miércoles','Jueves','Viernes','Sábado','Domingo'])[extract(isodow FROM d)::int],
       extract(isodow  FROM d) IN (6, 7)
FROM generate_series(DATE '2020-01-01', DATE '2035-12-31', INTERVAL '1 day') AS d;

--   dim_canal
INSERT INTO dwh.dim_canal (canal_key, codigo, canal) VALUES
    (-1, 'DESCONOCIDO', 'Desconocido'),
    ( 1, 'TIENDA',      'Tienda física'),
    ( 2, 'ONLINE',      'Online');

--   miembros "Desconocido" (-1)
-- Reciben los hechos cuya referencia no existe en el origen (ver reglas de calidad).
INSERT INTO dwh.dim_producto (producto_key, producto_id, producto, categoria, hash_atributos, vigente_desde)
VALUES (-1, -1, 'Desconocido', 'Desconocido', md5('Desconocido|Desconocido'), DATE '1900-01-01');

INSERT INTO dwh.dim_cliente (cliente_key, cliente_id, cliente, email, hash_atributos, vigente_desde)
VALUES (-1, -1, 'Desconocido', NULL, md5('Desconocido|'), DATE '1900-01-01');

INSERT INTO dwh.dim_geografia (geografia_key, ciudad_id, pais, departamento, provincia, ciudad)
VALUES (-1, -1, 'Desconocido', 'Desconocido', 'Desconocido', 'Desconocido');

INSERT INTO dwh.dim_empleado (empleado_key, empleado_id, empleado, cargo)
VALUES (-1, -1, 'Desconocido', NULL);

--   etl.control
-- Una fila por tabla fuente. La carga inicial parte del watermark en 1970.
INSERT INTO etl.control (fuente, tabla) VALUES
    ('OLTP',       'ciudad'),
    ('OLTP',       'categoria'),
    ('OLTP',       'producto'),
    ('OLTP',       'cliente'),
    ('OLTP',       'empleado'),
    ('OLTP',       'pedido'),
    ('CSV_ONLINE', 'ventas');
