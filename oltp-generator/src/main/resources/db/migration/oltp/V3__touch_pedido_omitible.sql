-- =====================================================================
-- VentasDW | OLTP V3: permitir que el generador cargue histórico conservando updated_at
--
-- El trigger de detalle_pedido "toca" el pedido (updated_at = now()) en cada cambio de línea. Para cargar
-- dos años de histórico con marcas de tiempo realistas, el generador activa la variable de sesión
-- ventasdw.omitir_touch dentro de su transacción (SET LOCAL) y el trigger no actúa.
-- En operación normal la variable no existe y el comportamiento es el de V1.
-- =====================================================================
CREATE OR REPLACE FUNCTION touch_pedido() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF current_setting('ventasdw.omitir_touch', true) = 'on' THEN
        RETURN NULL;
    END IF;
    IF TG_OP = 'DELETE' THEN
        UPDATE pedido SET updated_at = now() WHERE pedido_id = OLD.pedido_id;
    ELSE
        UPDATE pedido SET updated_at = now() WHERE pedido_id = NEW.pedido_id;
    END IF;
    RETURN NULL;
END;
$$;
