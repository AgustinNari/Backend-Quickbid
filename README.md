# QuickBid — Backend

Backend de QuickBid, una aplicación de subastas desarrollada en equipo. Expone una API REST para registro y autenticación, catálogo de subastas, pujas en tiempo real, compras, pagos, consignaciones, perfil y notificaciones. Incluye datos y herramientas de demostración.

La aplicación móvil está en [Frontend-Quickbid](https://github.com/AgustinNari/Frontend-Quickbid).

## Stack y estructura

Java 17, Spring Boot 4.0.6, Maven, Spring Security, JPA, PostgreSQL y Flyway. El proyecto ejecutable está en `quickbid/`: controladores REST, servicios de negocio, repositorios, entidades y adaptadores de correo y almacenamiento. Las tablas de la aplicación complementan el esquema legacy mediante migraciones versionadas.

La autenticación utiliza JWT y refresh tokens con rotación. Las pujas y los eventos de subastas se transmiten mediante WebSocket/STOMP. Los endpoints administrativos son auxiliares para operación manual y pruebas; no hay una interfaz de administración propia.

## Ejecución local

Se necesitan JDK 17 y una base PostgreSQL disponible. Crear una base vacía llamada `quickbid` y configurar las variables en la terminal que ejecutará el backend. Ejemplo en PowerShell:

```powershell
cd quickbid
$env:DB_URL = 'jdbc:postgresql://localhost:5432/quickbid'
$env:DB_USERNAME = 'postgres'
$env:DB_PASSWORD = '<clave-local>'
$env:APP_JWT_SECRET = '<secreto-aleatorio-de-al-menos-32-caracteres>'
.\mvnw.cmd spring-boot:run
```

En Linux/macOS, exportar las mismas variables y usar `./mvnw spring-boot:run`. El wrapper descarga Maven si no está disponible. Flyway aplica las migraciones al iniciar y JPA valida el esquema. El puerto predeterminado es `8080`; health está en `/actuator/health`.

## Configuración

| Variables | Uso |
| --- | --- |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | Conexión PostgreSQL. |
| `APP_JWT_SECRET` | Firma JWT; requiere al menos 32 caracteres. |
| `PORT` | Puerto HTTP; por defecto `8080`. |
| `APP_FILES_STORAGE_PATH` | Archivos subidos; por defecto `./uploads`. |
| `APP_MAIL_ENABLED`, `APP_MAIL_PROVIDER`, `APP_MAIL_FROM` | Correo; desactivado por defecto. Proveedores: `smtp`, `resend` o `brevo`. |
| `SPRING_MAIL_*`, `APP_RESEND_API_KEY`, `APP_RESEND_API_URL`, `APP_BREVO_API_KEY` | Credenciales y configuración del proveedor elegido. Resend requiere una URL de API explícita. |
| `APP_MAIL_NOTIFICATIONS_ENABLED` | Emails de eventos de negocio; por defecto `false`. |
| `APP_FRONTEND_BASE_URL`, `APP_PUBLIC_BASE_URL` | Destinos de enlaces de autenticación y URL pública del backend. Para la app móvil, configurar `APP_FRONTEND_BASE_URL=quickbid://auth`. |
| `APP_ADMIN_ENABLED`, `APP_ADMIN_INTERNAL_KEY` | Acceso administrativo auxiliar; desactivado por defecto. |

El detalle de opciones y valores predeterminados está en `quickbid/src/main/resources/application.properties`. No versionar credenciales reales. El perfil `dev` ofrece valores locales para PostgreSQL y muestra detalles de health; no debe utilizarse como perfil público de despliegue.

## Compilación y tests

Desde `quickbid/`:

```powershell
.\mvnw.cmd clean verify
```

Los tests utilizan el perfil `test`, H2 en memoria y datos de prueba propios; no requieren PostgreSQL ni correo real. Para ejecutar solo tests: `.\mvnw.cmd test`. Los ejemplos HTTP están en `quickbid/docs/api-examples.http`; `quickbid/docs/` incluye pruebas por flujo, paneles auxiliares y consultas SQL de validación. Algunas solicitudes modifican datos: usar una base de prueba.

## Docker

Desde `quickbid/`, `docker build -t quickbid-backend .` compila el JAR en una imagen con Java 17. Ejecutar con `docker run --rm -p 8080:8080 --env-file <archivo-local> quickbid-backend`. La base PostgreSQL debe ser accesible desde el contenedor; para conservar uploads, montar un volumen en la ruta configurada. El Dockerfile omite tests durante el empaquetado; ejecutarlos antes de construir la imagen.

## Alcance de demostración

Las migraciones incluyen cuentas y datos ficticios de demo. Las opciones `APP_DEMO_*` controlan herramientas de demostración; no constituyen una integración comercial de pagos. La simulación de fallo externo de adjudicación está habilitada por defecto con una probabilidad del 1 %, configurable mediante `APP_PAYMENT_ADJUDICATION_EXTERNAL_FAILURE_ENABLED` y `APP_PAYMENT_ADJUDICATION_EXTERNAL_FAILURE_PROBABILITY_PERCENT`.

Con correo desactivado, la entrega es simulada y no permite recibir enlaces reales de registro o recuperación. El broker STOMP, la presencia y el rate limiting mantienen estado en memoria por instancia. El almacenamiento de archivos es local. Estas condiciones deben considerarse al reproducir la demo o desplegar varias instancias.
