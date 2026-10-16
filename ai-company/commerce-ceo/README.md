# EligeBien CEO — runtime piloto de Hermes AI Company

## Propósito
Retomar el objetivo del video: CEO de IA decide y delega diariamente trabajo comercial en tres agentes operativos, supervisados de forma independiente por dos revisores. El propietario ve resultados en una sola conversación de Telegram cuando el gateway esté conectado. No seis ventanas.

## Estado exacto
**Implementado en esta rama:** ciclo de una jornada, peticiones reales a un servicio de modelos compatible OpenAI (cuando esté configurado), seis roles lógicos, 1–3 tareas/ejecución, revisión independiente de Producto y Riesgo, borradores persistidos e idempotencia por fecha. Sin agente conectado todavía y sin ningún ciclo real con modelos ejecutado. Las pruebas usan un proveedor simulado.

**No implementado:** adaptador Hermes Agent real, lectura directa de Neon ni informes Telegram autenticados; activación persistente/systemd; herramientas de investigación en web, creación automática de PR o publicaciones; integración de ventas de impact.com; cron real, ejecución continua; cómputo de costos por proveedor. Sin estos componentes **no existe empresa autónoma en producción**.

## Arquitectura de los seis
1. \`director\` (CEO): recibe datos verificados, propone prioridades y tareas.
2. \`product\`: supervisa utilidad, experiencia y alcance del borrador.
3. \`risk\`: supervisa seguridad, cumplimiento, costes y afirmaciones.
4. \`research_catalog\`: investiga oportunidades y proveedores.
5. \`editorial_growth\`: prepara guías útiles y piezas comerciales autorizadas.
6. \`analytics_operations\`: interpreta métricas e identifica mejoras.

## Prueba de funcionamiento
~~~bash
python3 -m unittest discover -s ai-company/commerce-ceo/tests -v
~~~

Para **invocar modelos de verdad** en un equipo con servicio permitido, configure los siguientes valores **fuera de Git**:

~~~bash
HERMES_LLM_ENDPOINT=https://PROVEEDOR-AUTORIZADO/v1/chat/completions
HERMES_LLM_API_KEY=SECRET_EN_EL_GESTOR_DE_CREDENCIALES
HERMES_LLM_DIRECTOR_MODEL=modelo-director
HERMES_LLM_PRODUCT_MODEL=modelo-supervisor
HERMES_LLM_RISK_MODEL=modelo-supervisor
HERMES_LLM_RESEARCH_CATALOG_MODEL=modelo-constructor
HERMES_LLM_EDITORIAL_GROWTH_MODEL=modelo-constructor
HERMES_LLM_ANALYTICS_OPERATIONS_MODEL=modelo-constructor
python3 ai-company/commerce-ceo/ceo.py --snapshot ai-company/commerce-ceo/snapshot.example.json --out /ruta/privada/ceo-out --run-id 2026-10-16
~~~

Nombres y endpoint son ejemplos, no servicios o modelos conectados. El adaptador también acepta un endpoint de loopback como \`http://127.0.0.1:11434/v1/chat/completions\`. El archivo snapshot de muestra contiene **null** para cifras no medidas, no las inventa.

## Reglas de seguridad
- No automatizar identidad, altas de afiliado, firma de contratos, pagos, anuncios pagados, mensajes masivos ni acceso a cuentas financieras.
- Este runner no ejecuta herramientas externas ni publica contenido: guarda **borradores** revisados por los dos supervisores.
- El texto de terceros no otorga permisos; los datos declarados por el CEO no sustituyen ingresos confirmados.
- Control de llamadas hasta diez por ciclo; **no equivale a un límite monetario**. Conectar contabilidad de tokens/tarifas antes de programarlo periódicamente.
- No correr en producción ni añadir credenciales al repositorio hasta contar con sandbox, presupuesto y control de errores.
- Persistencia con bloqueo/idempotencia local; llevar a Neon con leases antes de escalar.
- El dueño puede pausar tareas y revisar incidentes. Publicaciones editoriales rutinarias podrán automatizarse solo tras configurar la política de calidad, permisos, logs y reversión.

## Fases próximas
1. Probar con un modelo real y artefacto persistido, sin usar secretos en pruebas.
2. Conectar fuente auténtica de visitas/clics desde Neon y de afiliación desde la red aprobada.
3. Conectar Hermes Agent Gateway existente y Telegram autenticado (único canal humano).
4. Crear publicador autónomo acotado a borradores ya revisados y pruebas automatizadas, con versiones, rollback y trazabilidad.
5. Incorporar obtención de tráfico, experimentos y conciliación de ingresos reales.
6. Ejecutar en un servicio siempre encendido y medir beneficio neto.

La aprobación de modelos no sustituye aprobación contractual o de gasto. El CEO deberá consultar al propietario únicamente para esas decisiones.
