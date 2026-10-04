-- =====================================================================
-- VentasDW | OLTP V3: permitir que el generador cargue historico conservando updated_at
 
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
