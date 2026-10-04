-- VentasDW | V1: esquemas, extensiones y roles de grupo

CREATE SCHEMA IF NOT EXISTS stg;   
CREATE SCHEMA IF NOT EXISTS dwh;    
CREATE SCHEMA IF NOT EXISTS etl;   

COMMENT ON SCHEMA stg IS 'Staging del ETL: datos crudos y en transformacion. Se puede truncar entre ejecuciones.';
COMMENT ON SCHEMA dwh IS 'Data Warehouse: esquema estrella (dimensiones y hechos) y agregados.';
COMMENT ON SCHEMA etl IS 'Metadatos del proceso ETL: control incremental, historial de ejecuciones y excepciones.';

CREATE EXTENSION IF NOT EXISTS btree_gist;
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
