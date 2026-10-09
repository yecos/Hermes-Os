# Hermes AI Company — primera entrega

Módulo experimental aislado dentro de Hermes OS. El propietario utilizará **una sola conversación con el Director en Telegram**, por medio del gateway de mensajería que ya incorpora Hermes Agent; **no crearemos otro bot**.

## Modo ECO equilibrado (preparado, NO activo en Hermes)

La política Codex-only está en [`eco-policy.json`](eco-policy.json) y su guía en
[`ECO-MODE.md`](ECO-MODE.md). `eco_mode.py` implementa reservas SQLite,
cuotas por rol/proyecto, protección contra duplicados, backoff global ante
HTTP 429 y un registro de tokens recibidos mediante telemetría. Sus tests
están en `tests/test_eco_mode.py`.

**Pendiente imprescindible:** conectar la guardia al despachador real de
sesiones Hermes. Publicar este módulo no cambia automáticamente los seis
perfiles del PC ni mide el consumo privado de la suscripción.

## Estado actual

**Implementado y probado localmente:** motor de proyectos, tareas, bitácora de eventos, evidencia obligatoria, revisión funcional y técnica por separado, escalamiento por errores y solicitudes de aprobación ligadas al SHA exacto de un commit. Usa SQLite únicamente para este prototipo inicial.

**Fase 2 en desarrollo:** `phase2.py` añade el primer bridge verificable desde
una sesión real del gateway: valida los metadatos de un DM Telegram contra una
allowlist, deduplica el mensaje, enlaza IDs reales de `delegate_task`, verifica
el commit producido por un constructor, vuelve a ejecutar las pruebas y exige
revisiones Product/Architect independientes ligadas al mismo SHA. Consulta
[`PHASE-2-NATIVE-RUNBOOK.md`](PHASE-2-NATIVE-RUNBOOK.md).

**Todavía pendiente:** servicio duradero para reanudar delegaciones tras un
reinicio, seis perfiles/modelos completos, Neon, gestión automática de
worktrees, previews y despliegue. La aprobación del prototipo nunca publica
código.

### Prueba local

Requiere Python 3.11+ y no necesita paquetes externos.

```bash
python -m unittest discover -s ai-company/tests -v
python ai-company/smoke_demo.py  # simulación TEMPLO, no usa modelos, Telegram ni despliegue
python ai-company/company.py --db hermes-company.sqlite3 new "TEMPLO demo" "Cotizaciones"
python ai-company/company.py --help
```

Usa el identificador devuelto para crear tareas, entregarlas, revisarlas y consultar su estado. No publiques archivos .sqlite3 en Git.

### Los seis roles

| Rol interno | Nivel | Trabajo |
| --- | --- | --- |
| director | Alto | Telegram, estrategia, prioridades, consultas y entregas |
| product | Medio | Requisitos, diseño, experiencia y revisión funcional |
| architect | Medio | Arquitectura, seguridad, calidad y revisión técnica |
| frontend | Económico | Interfaces y componentes |
| backend | Económico | APIs, datos y lógica |
| integrations | Económico | Integraciones, automatizaciones y pruebas |

Los tres constructores no pueden aprobar su propia tarea. Una entrega solo se considera aceptada cuando **product y architect** la aceptan, con evidencia. Al segundo rechazo se escala técnicamente y al tercero se bloquea para atención del Director.

### Modelos por agente

La configuración versionada y sin secretos está en
[`agent-models.json`](agent-models.json), validada por `agent_models.py` y su
suite. La política es `strict_maximum` y fija techos independientes:
Director usa como máximo `gpt-5.6-sol`; Product y Architect, `gpt-6-luna`;
Frontend, Backend e Integrations, `gpt-5.6-luna`. Los tres modelos aparecen
en el catálogo vivo de `openai-codex` y respondieron a pruebas reales de
conectividad. Ningún rol puede heredar silenciosamente un modelo superior; si
un modelo deja de estar disponible, su estado pasa a `pending_authorization`
y el campo `model` queda vacío hasta que el propietario autorice un equivalente
de igual o menor capacidad. Los límites de iteraciones, tiempo, salida,
toolsets y permisos también varían por rol. Las credenciales permanecen en
Hermes y nunca se guardan en este repositorio.

Estado comprobado del tracer bullet anterior: Director, Product, Architect y
Backend ejecutaron trabajo real con la configuración previa. Tras aplicar los
techos corregidos, los tres modelos seleccionados tienen conectividad
verificada; una nueva ejecución funcional por rol queda para el siguiente
circuito, sin reescribir la evidencia histórica.

### Seguridad

- Este código es un **motor de workflow**, no una barrera de autenticación de producción. Antes de conectar Telegram es obligatorio verificar la identidad del propietario con una allowlist real.
- El método Python `approve_preview` presupone que el adaptador externo autenticó al humano: no se debe aceptar el parámetro `actor` enviado por un usuario sin verificar. Por ello no existe un comando CLI para aprobar.
- Las solicitudes de aprobación se asocian al hash completo del commit; cambiar la versión invalida una aprobación pendiente anterior.
- **No hay ejecutor de despliegue** en esta etapa: la aprobación es solo un registro en la base local.
- No se modifican los servicios existentes ni las credenciales de Hermes OS.

### Próxima secuencia

1. Integración con Hermes Agent Gateway y Telegram para crear proyectos/consultar estados en lenguaje natural.
2. Conexión de tres modelos constructores y dos supervisores con sesiones/identidades aisladas, y un Director.
3. Migración de estado y cola de trabajo a Neon con reanudación segura.
4. Worktrees, pruebas automatizadas y revisiones.
5. Previews Vercel, aprobación humana de una versión concreta y ejecutor de publicación controlado.

Consulta el [protocolo de operación](PROTOCOL.md) para reglas de autoridad, estados y escalamiento.

El primer tracer bullet de los pasos 1–2 está documentado en el
[runbook de delegación nativa](PHASE-2-NATIVE-RUNBOOK.md); no debe confundirse
con la operación duradera de los seis roles.

Windows + perfiles Hermes 0.20.2: [ECO-WINDOWS-RUNBOOK.md](ECO-WINDOWS-RUNBOOK.md) contiene la instalacion, validacion, rollback y limites reales del Modo ECO.
