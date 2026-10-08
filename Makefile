# Carga .env (si existe) y lo exporta, para que mvn spring-boot:run use las mismas credenciales que docker compose
-include .env
export
DWH_OWNER_USER  ?= dwh
OLTP_OWNER_USER ?= oltp

.PHONY: init infra-up apps-up down reset psql-dwh psql-oltp install-common run-generator run-etl run-api test verify-dwh generar-dia etl etl-total etl-historial etl-qa olap-metadatos olap-ejemplo

init:            ## Crea .env a partir de .env.example (si no existe)
	@test -f .env || cp .env.example .env

infra-up: init   ## Levanta PostgreSQL (x2), Redis, Prometheus y Grafana
	docker compose up -d

apps-up: init    ## Ademas construye y levanta los tres servicios Spring Boot
	docker compose --profile apps up -d --build

down:            ## Detiene todo (conserva los datos)
	docker compose --profile apps down

reset:           ## Detiene todo y BORRA los volumenes (datos y migraciones aplicadas)
	docker compose --profile apps down -v

psql-dwh:        ## Consola SQL del DWH como dueño
	docker compose exec postgres-dwh psql -U $(DWH_OWNER_USER) -d dwh

psql-oltp:       ## Consola SQL del OLTP como dueño
	docker compose exec postgres-oltp psql -U $(OLTP_OWNER_USER) -d oltp

# spring-boot:run falla en los modulos sin clase main (pom padre y dwh-common) si se usa -am.
# Por eso se instala primero dwh-common en el repositorio local y luego se ejecuta solo el servicio.
install-common:  ## Instala el pom padre y dwh-common en el repositorio Maven local
	mvn -B -q -DskipTests -pl dwh-common -am install

run-generator: install-common   ## Ejecuta oltp-generator (aplica las migraciones del OLTP, puerto 8082)
	mvn -pl oltp-generator spring-boot:run

run-etl: install-common         ## Ejecuta etl-service (aplica las migraciones del DWH, puerto 8081)
	mvn -pl etl-service spring-boot:run

run-api: install-common         ## Ejecuta olap-api (puerto 8080)
	mvn -pl olap-api spring-boot:run

test:            ## Pruebas unitarias e integracion (requiere Docker para Testcontainers)
	mvn -B verify

verify-dwh:      ## Prueba el DWH con datos de ejemplo (corre dentro de una transaccion con ROLLBACK)
	@docker compose exec -T postgres-dwh psql -U $(DWH_OWNER_USER) -d dwh -tAc "SELECT to_regclass('dwh.fact_ventas') IS NOT NULL" | grep -q t \
	  || { echo "El DWH aun no tiene las migraciones. Ejecuta primero 'make run-etl' (o 'make apps-up') y espera a que arranque."; exit 1; }
	docker compose exec -T postgres-dwh psql -U $(DWH_OWNER_USER) -d dwh -v ON_ERROR_STOP=1 < db/verificar_dwh.sql

generar-dia:     ## Dispara el flujo diario del generador (opcional: make generar-dia FECHA=2026-10-03)
	curl -s -X POST "http://localhost:8082/api/v1/generador/flujo-diario$(if $(FECHA),?fecha=$(FECHA),)"; echo

etl:             ## Ejecuta el ETL (carga inicial la primera vez, incremental despues)
	@curl -s -X POST "http://localhost:8081/api/v1/etl/ejecutar"; echo

etl-total:       ## Reconstruye el DWH desde cero (la historia SCD2 se recalcula)
	@curl -s -X POST "http://localhost:8081/api/v1/etl/ejecutar?modo=total"; echo

etl-historial:   ## Ultimas ejecuciones del ETL
	@curl -s "http://localhost:8081/api/v1/etl/ejecuciones?limite=10"; echo

etl-qa:          ## Errores inyectados por el generador contra errores detectados por el ETL
	@curl -s "http://localhost:8081/api/v1/etl/calidad/comparacion-qa"; echo

olap-metadatos:  ## Dimensiones, jerarquias y medidas de la API OLAP
	@curl -s "http://localhost:8080/api/v1/olap/metadatos"; echo

olap-ejemplo:    ## Ventas por año y categoria (agregado materializado); mas ejemplos en requests/olap.http
	@curl -s -i -X POST "http://localhost:8080/api/v1/olap/consulta" -H "Content-Type: application/json" -d '{"filas":["fecha.anio","producto.categoria"],"medidas":["ventas","unidades"]}' | grep -E "^(HTTP|X-Cache)|^[{]"; echo
