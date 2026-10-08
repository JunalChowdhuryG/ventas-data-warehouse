-- =====================================================================
-- VentasDW | V1: esquemas, extensiones y roles de grupo
-- Base de datos objetivo: PostgreSQL 16 (instancia postgres-dwh)
-- =====================================================================

-- Zonas de datos (ver sección 5.2 del documento de diseño)
CREATE SCHEMA IF NOT EXISTS stg;   -- staging: copias temporales y resultados intermedios
CREATE SCHEMA IF NOT EXISTS dwh;   -- dimensiones, hechos y vistas materializadas
CREATE SCHEMA IF NOT EXISTS etl;   -- control del proceso: watermarks, ejecuciones, excepciones

COMMENT ON SCHEMA stg IS 'Staging del ETL: datos crudos y en transformación. Se puede truncar entre ejecuciones.';
COMMENT ON SCHEMA dwh IS 'Data Warehouse: esquema estrella (dimensiones y hechos) y agregados.';
COMMENT ON SCHEMA etl IS 'Metadatos del proceso ETL: control incremental, historial de ejecuciones y excepciones.';

-- Necesaria para la restricción de exclusión que impide versiones SCD2 solapadas
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Roles de grupo sin login. Los usuarios con contraseña se crean fuera de las
-- migraciones (script de inicialización del contenedor) y se agregan a estos grupos.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dwh_etl_rw') THEN
    CREATE ROLE dwh_etl_rw NOLOGIN;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dwh_api_ro') THEN
    CREATE ROLE dwh_api_ro NOLOGIN;
  END IF;
END
$$;
