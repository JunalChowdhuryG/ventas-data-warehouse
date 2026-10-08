#!/bin/bash
# Se ejecuta una sola vez, al crear el volumen de postgres-dwh (docker-entrypoint-initdb.d).
# Crea los roles de grupo y los usuarios de runtime. Las contraseñas vienen del entorno del contenedor.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v etl_pw="$DWH_ETL_PASSWORD" -v api_pw="$DWH_API_PASSWORD" <<'EOSQL'
-- Grupos (la migración V1 los crea solo si no existen)
CREATE ROLE dwh_etl_rw NOLOGIN;
CREATE ROLE dwh_api_ro NOLOGIN;

-- Usuarios de runtime
CREATE ROLE u_etl LOGIN PASSWORD :'etl_pw' IN ROLE dwh_etl_rw;
CREATE ROLE u_api LOGIN PASSWORD :'api_pw' IN ROLE dwh_api_ro;
EOSQL
