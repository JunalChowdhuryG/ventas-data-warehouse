#!/bin/bash
# Se ejecuta una sola vez, al crear el volumen de postgres-oltp.
# Crea el usuario de solo lectura que usa el ETL para extraer.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v ro_pw="$OLTP_RO_PASSWORD" -v owner="$POSTGRES_USER" <<'EOSQL'
CREATE ROLE oltp_ro LOGIN PASSWORD :'ro_pw';
GRANT USAGE ON SCHEMA public TO oltp_ro;
-- Las tablas las crea después Flyway (oltp-generator) como dueño: heredan SELECT para oltp_ro
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner" IN SCHEMA public GRANT SELECT ON TABLES TO oltp_ro;
EOSQL
