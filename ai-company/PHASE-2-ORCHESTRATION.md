# Hermes AI Company — Fase 2: orquestación real

Estado: **diseño e implementación pendientes**, no confundir con el motor H1, que ya pasó pruebas de flujo simulado.

## Decisión de arquitectura

Hermes Agent (gateway de Telegram existente) es el punto de entrada y el **Director**. El módulo `ai-company` es la fuente autoritativa de estados, tareas, permisos y evidencias. Una capa nueva, **Runner**, levanta sesiones independientes de IA a través de adaptadores de proveedor y herramientas de ejecución limitadas. Hermes Commander / Hermes Node ofrece el control de sistema en equipos autorizados, sin conceder acceso administrativo a todos los agentes.

No mantener seis ventanas de ChatGPT. Los seis roles son **sesiones lógicas**, con sus propias instrucciones, presupuestos, memoria de proyecto y permisos.

## Roles y delegaciones

| Rol | Perfil de modelo | Produce | Puede |
|---|---|---|---|
| Director | Alto | Decisiones, prioridades, preguntas Telegram, resumen | Crear/proponer/pausar tareas, solicitar aprobación del usuario |
| Producto | Medio | Historias de usuario, criterios, revisión UX | Planificar y aprobar/rechazar funcionalidad |
| Arquitectura | Medio | Diseño técnico, contratos, revisión técnica y seguridad | Planificar y aprobar/rechazar implementación |
| Frontend | Económico | Componentes, estilos, pruebas UI | Escribir solo en su worktree |
| Backend | Económico | APIs, datos, pruebas de lógica | Escribir solo en su worktree |
| Integraciones | Económico | Conectores, automatización, pruebas E2E | Escribir solo en su worktree |

Ningún modelo puede inventarse permisos. Ninguno puede publicar o modificar datos reales sin autorización específica y verificación en código.

## Contrato de ejecución (objetivo próximo)

Entrada a Runner, validada contra esquema:

```json
{
  "job_id": "unique-id",
  "project_id": "project-id",
  "task_id": "task-id",
  "role": "backend",
  "instructions": "Implementar endpoint de ejemplo",
  "acceptance_criteria": ["Validar datos", "Pruebas pasan"],
  "dependencies": [],
  "repository": "owner/repo",
  "base_sha": "40-hex-sha",
  "workspace_id": "isolated-worktree",
  "max_runtime_seconds": 900,
  "max_cost_usd": 0.5
}
```

Salida:

```json
{
  "job_id": "unique-id",
  "status": "submitted_for_review",
  "head_sha": "40-hex-sha",
  "changed_files": ["path/file.ext"],
  "commands_executed": ["npm test"],
  "test_results": [{"name": "npm test", "passed": true}],
  "evidence": ["artifact-or-log-reference"],
  "cost_usd": 0.12,
  "error": null
}
```

Los valores anteriores son **ejemplos de contrato**, no trabajos ejecutados ni tarifas confirmadas. El Runner debe registrar el artefacto real y el ID de ejecución.

## Máquina de estados que debe respetarse

1. `requested`: llega mensaje; autenticar el emisor por ID, nunca por el texto del mensaje.
2. `specified`: Director con Producto y Arquitectura generan alcance y criterios de aceptación.
3. `queued`: validar permisos, dependencias, presupuesto, repositorio y rama base.
4. `claimed`: asignar tarea a UN worker mediante lease persistente e idempotencia; al expirar se puede reintentar sin duplicar efectos.
5. `working`: ejecutar sesión de agente en un worktree/contenedor aislado, con comandos permitidos.
6. `review`: evidencias y tests reales; revisores Producto y Arquitecto independientes.
7. `accepted` o `queued/blocked`: dos aprobaciones o corrección/escalamiento.
8. `preview`: ejecutar CI y crear versión de prueba, identificada por commit exacto.
9. `owner_approval`: Telegram presenta resumen y pide confirmación explícita de la versión exacta.
10. `deploy`: worker de publicación independiente verifica permisos, SHA, CI, destino y rollback.

H1 implementa solo un subconjunto de estos estados. NO se debe describir H1 como Runner operativo.

## Orden de implementación sin panel web

### H2.A — Runner funcional mínimo

- Separar `AgentBackend` (modelo, generación y uso de herramientas) de `WorkflowStore` (estado).
- Elegir adaptador inicial para Hermes Agent / Codex disponible; no asumir sintaxis de comandos hasta comprobar la instalación real.
- Preparar credenciales locales fuera de Git; configurar los perfiles alto/medio/económico sin fijar un proveedor.
- Introducir jobs persistentes con leasing, expiración, cancelación, heartbeats, deduplicación y presupuestos.
- Ejecutar **una** tarea real contra un proyecto de demostración vacío, sin despliegue.
- Validar que el artefacto provenga de cambios reales y que sus pruebas corrieron, no solo de una afirmación del modelo.

### H2.B — Pirámide 1–2–3 completa

- Director estructura un brief; Producto y Arquitectura convierten el brief en tareas.
- Hasta tres builders paralelos, cada uno en rama y worktree aislados; dependencias explícitas y control de archivos.
- Ambos revisores firman contra la misma revisión exacta del código; una modificación posterior invalida las firmas.
- Si falta evidencia o falla CI, bloquear promoción aunque los modelos digan que la tarea está lista.

### H2.C — Telegram como única conversación

- Reutilizar la integración Telegram del gateway de Hermes Agent.
- Adaptador de entrada/salida con ID de usuario/chat allowlisted, anti-replay e IDs de mensajes deduplicados.
- Mensajes en lenguaje natural: crear, consultar progreso, aportar decisión, pedir captura, pausar/reanudar.
- Director consolida mensajes; las otras cinco sesiones nunca escriben directamente al propietario.
- Autorización de producción: autenticación real + aprobación vinculada a SHA/destino; no aceptar una etiqueta de actor suministrada por el modelo.
- Si el propietario no responde, `awaiting_decision` sin publicación automática.

### H2.D — Neon, GitHub y Vercel

- Migrar cola/bitácora a Neon antes de ejecutar workers distribuidos; mantener transacciones y leases.
- PR y CI con GitHub; capturas/pruebas visuales con Playwright; previews por commit en Vercel.
- Ejecutar publicación por servicio separado con credenciales acotadas y política de rollback.

## Primer criterio de éxito medible

Desde un mensaje **real y autenticado** de Telegram sobre «Cotizaciones TEMPLO»:
1. Director crea proyecto y genera un brief trazable.
2. Producto y Arquitectura producen al menos una tarea verificable.
3. **Un constructor real** modifica código en un repositorio de demostración aislado.
4. Se ejecutan pruebas reales y se guardan resultados y commit.
5. Los dos supervisores aprueban o devuelven correcciones.
6. Director envía un resumen real por Telegram, sin publicar producción.

Solo cuando ese circuito funcione se habilitan los tres constructores concurrentes, previews y, por último, el despliegue con aprobación humana.

## Reglas de control

- Presupuesto por proyecto, tarea y modelo; tiempo máximo y número máximo de reintentos.
- No compartir secretos con builders. No conceder `-FullControl` de Hermes Commander a agentes no supervisados.
- Permisos por repo, ruta y operación. Aislamiento de red y filesystem en workers.
- Mantener bitácora de modelos, prompt versionado, costo/uso, resultado, SHA, revisión y decisión.
- Nunca ejecutar comandos recibidos literalmente por Telegram sin pasar por un plan autorizado.
- Si un worker se desconecta, conservar estado y reanudar de manera idempotente.
