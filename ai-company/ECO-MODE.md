# Modo ECO equilibrado — Codex únicamente

**Estado de esta entrega:** política versionada, guardia SQLite y pruebas automatizadas. **NO** está conectado todavía al lanzamiento real de las seis sesiones Hermes: integrar el despachador es el trabajo pendiente. No se ha cambiado la configuración local del PC ni el gateway de Telegram.

## Objetivo y límites

- Mantener `openai-codex` y los modelos máximos estrictos ya autorizados: Director `gpt-5.6-sol`; Producto/Arquitectura `gpt-6-luna`; Frontend/Backend/Integraciones `gpt-5.6-luna`.
- Reducir las iteraciones máximas **por ejecución** a 12/8/12 respectivamente, con máximos de tiempo y salida acotados por rol. Estos son objetivos del despachador; `agent-models.json` conserva sus techos generales.
- Una ejecución de constructor y una de revisión simultáneas; la segunda revisión del mismo SHA ocurre después de la primera. No usar subdelegación recursiva ni sesiones nuevas para formateo, Git, JSON y pruebas deterministas.
- Limitar inicialmente a 24 inicios diarios por proyecto y 8 por rol/proyecto, con error explícito y solicitud de autorización al agotar el límite. Los días se cuentan en UTC.
- Después de un 429, pausar **todo `openai-codex`** durante 5, 15 o 60 minutos con backoff incremental. No cambiar silenciosamente de modelo ni saltar los límites desde otro perfil.

## Uso de la guardia programática

`eco_mode.py` implementa una reserva transaccional por `run_id` y controles de concurrencia. El despachador deberá asignar un `run_id` durable e idempotente, **antes** de abrir el proceso del perfil. El estado `running` NO vence automáticamente: si se reinicia Windows, reconciliar el PID, la sesión y los artefactos antes de marcar el resultado o crear otro intento.

Ejemplo manual en un workspace de prueba:

```bash
python ai-company/eco_mode.py --db ai-company-eco.sqlite3 reserve --project demo-eco --role backend --run-id demo-eco-backend-001
python ai-company/eco_mode.py --db ai-company-eco.sqlite3 finish --run-id demo-eco-backend-001 --status ok --session-id SESSION_ID
python ai-company/eco_mode.py --db ai-company-eco.sqlite3 report --project demo-eco
```

El primer comando **NO** lanza Hermes ni consume tokens. El segundo registra el resultado sin inventar métricas; si no se suministran tokens, quedan como desconocidos. Cuando Hermes proporcione datos reales, el despachador debe pasar `--input-tokens`, `--output-tokens`, `--cached-input-tokens` y `--usage-source hermes`, o `provider` si son cifras directas del proveedor. Los tokens en caché son parte de los tokens de entrada y no se suman dos veces.

Ejemplo de reporte real atribuido (usar SOLO números extraídos de telemetría real):

```bash
python ai-company/eco_mode.py --db ai-company-eco.sqlite3 finish --run-id ID --status ok --session-id SESSION_ID --input-tokens 1000 --output-tokens 200 --cached-input-tokens 600 --usage-source hermes
```

Estos números son exclusivamente un ejemplo de sintaxis y no representan uso observado.

## Orden de integración para Hermes

1. Verificar los comandos realmente admitidos por Hermes 0.20.2 para iniciar/reanudar sesiones por perfil (`companydirector`, `companyproduct`, `companyarchitect`, `companyfrontend`, `companybackend`, `companyintegrations`). Mantener un único gateway Telegram del Director.
2. Conectar la llamada a `EcoUsageLedger.reserve` **antes** de cada lanzamiento de perfil. Tras reservar, iniciar o reanudar el perfil correcto y verificar su modelo. El run ID no debe ser un texto inventado por el agente, sino un identificador derivado del trabajo durable.
3. Aplicar `eco-policy.json` a los límites reales de la ejecución, sin afirmar que el JSON por sí solo altera el comportamiento de Hermes. Minimizar prompts usando criterio de aceptación, diff de Git, archivos afectados y SHA, no toda la conversación.
4. Cerrar la reserva con `finish` únicamente después de capturar el resultado de la sesión. Registrar tokens solo cuando Hermes o el proveedor los entreguen; nunca derivarlos de longitud de mensajes.
5. En un 429, `finish(status='rate_limited')`, suspender el lanzamiento de **cualquier** perfil Codex y avisar al Director. No abrir sesiones extra para investigar la falla.
6. En una caída del proceso, conservar `running` y conciliar proceso real, ID de sesión y SHA antes de cancelar o recuperar. La guardia es local SQLite; no usarla como mecanismo multi-host sin migrar a una DB transaccional apropiada.
7. Ejecutar pruebas mecánicas localmente, sin subagentes. Mantener los revisores independientes sobre el mismo SHA antes de aprobación humana y sin despliegue.

## Medición honesta

**Línea base:** conservar resultados reales previos de `/usage`, `/insights 1` o estadísticas nativas del proveedor, indicando ventana horaria, rol, modelo y fuente. Si faltan datos, el punto de partida queda `no medido`.

**Piloto ECO:** registrar 1 tarea real con constructor y 2 revisiones, asociando los tres IDs de sesión; comparar el número de ejecuciones, iteraciones, tokens de entrada/salida, tokens de entrada en caché, pausas 429 y tiempo total frente a una tarea histórica comparable. Reportar sesiones sin telemetría como `unknown`, nunca como consumo cero.

**Limitación:** la cifra de tokens del ledger **no es el porcentaje de cuota de ChatGPT ni el costo monetario de la suscripción**. Para comparar la cuota real consultar el estado de uso del proveedor, si está disponible. No anunciar porcentajes de ahorro sin línea base comparable.

## Pruebas incluidas

```bash
python -m unittest discover -s ai-company/tests -v
python ai-company/eco_mode.py --help
```

Los tests comprueban configuración de modelos, rechazo de cambios no autorizados, reserva idempotente, concurrencia, presupuesto diario, cooldown global 429 y backoff creciente, diferencia entre tokens medidos/desconocidos y bloqueo conservador después de un reinicio.

**Sin merge, sin preview, sin producción.**
