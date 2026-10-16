# Puente firmado entre Hermes Agent Gateway y CEO

Estado: código en preparación; NO está conectado al bot Telegram ni a modelos reales.
Este puente recibe sobres firmados por un adaptador confiable del gateway EXISTENTE de Hermes Agent. No crea otro bot.

## Contrato de confianza
El adaptador debe obtener update_id, user_id y chat_id directamente de Telegram, NO del texto del mensaje o de una inferencia del agente.
Los datos de transporte se serializan como JSON con claves ordenadas y separadores compactos. El gateway aislado los firma con HMAC-SHA256 usando una clave privada de al menos 32 bytes. El firmador jamás estará disponible como herramienta para los modelos.
Campos: update_id (string numérico), user_id (string), chat_id (string), issued_at (segundos UTC), command (status | report | pause | resume | run). El verificador comprueba identidad, firma, caducidad de cinco minutos y rechazo de update_id repetidos. Empezará PAUSADO.

Comandos previstos: /ceo estado, /ceo informe, /ceo pausa, /ceo reanuda, /ceo trabaja. Estos comandos deben ser reconocidos por el adaptador del gateway tras validar la identidad. El parser y el emisor del gateway Hermes Agent NO están conectados aún; no interpretar las órdenes de lenguaje natural como autenticación.

## Datos y ejecución
Una solicitud run solo se agrega a SQLite: NO ejecuta modelos, no publica contenido, no gasta dinero y no firma convenios. El ciclo de CEO experimental sigue produciendo borradores hasta que exista un ejecutor con presupuesto y permisos reales.
El lector metrics_snapshot.py consulta solo recuentos agregados de event_type durante los últimos siete días desde Neon usando psycopg y ELIGEBIEN_METRICS_DATABASE_URL, con credenciales guardadas fuera de Git. visits permanece null (sin medición). Comisiones aprobadas permanecen null hasta tener confirmación de la red de afiliados.

Ejemplo de ejecución del verificador, SOLO con metadatos y firmas que genere el gateway confiable:
python3 ai-company/commerce-ceo/telegram_control.py --envelope /ruta/privada/update.json --signature /ruta/privada/signature.txt --db /ruta/privada/ceo/control.sqlite3 --reports /ruta/privada/ceo/reports

Variables a configurar en un entorno protegido: HERMES_OWNER_USER_ID, HERMES_OWNER_CHAT_ID y HERMES_GATEWAY_HMAC_KEY. Nunca incluir valores en repositorios, chats o capturas.

Ejemplo del agregador: python3 ai-company/commerce-ceo/metrics_snapshot.py --partners apps/eligebien/commerce/partner-candidates.json --out /ruta/privada/ceo/snapshot.json

## Activación real pendiente
1. Verificar que el servicio Hermes Agent y el gateway Telegram original están activos en el host autorizado.
2. Inspeccionar y adaptar las APIs reales del gateway. No suponer rutas ni firmas no verificadas.
3. Implementar el emisor firmado en un proceso aislado; probar identidad real del propietario en Telegram.
4. Conectar los modelos, cola con lease persistente, límites de costos, reportes Telegram y un worker systemd.
5. Ensayar un ciclo real con tareas, evidencia y dos revisiones, sin publicación automática.
6. Habilitar más adelante publicaciones editoriales acotadas con CI/rollback. Contratos, pagos y anuncios pagados requieren aprobación humana.

Pruebas: python3 -m unittest discover -s ai-company/commerce-ceo/tests -v