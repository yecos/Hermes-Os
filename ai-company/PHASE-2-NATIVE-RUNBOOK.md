# Phase 2 — primer circuito nativo

Este hito conecta el motor H1 con una conversación real de Hermes Agent sin
convertir afirmaciones del modelo en evidencia. El **Director es la sesión de
Hermes que recibió el mensaje autenticado de Telegram**. Esa sesión usa su
herramienta nativa `delegate_task` para lanzar un constructor y dos supervisores.
El módulo `phase2.py` conserva el estado y verifica los efectos observables.

## Alcance implementado

```text
Telegram DM autenticado
        │ metadatos HERMES_SESSION_* del gateway
        ▼
TelegramDirectorBridge ── deduplicación (chat_id, message_id)
        │
        ▼
Company / SQLite ── proyecto + tarea
        │
        ▼
Director prepara native_run contra base_sha
        │
        ├── delegate_task → builder (worktree/repo aislado)
        │                    └── cambio + pruebas + commit
        │
        ├── verificación local independiente
        │     - HEAD exacto
        │     - descendiente de base_sha
        │     - archivos realmente modificados
        │     - reejecución del comando aprobado por el Director
        │     - hash SHA-256 de la salida
        │
        └── delegate_task en paralelo → Product + Architect
                              └── ambos revisan el mismo head_sha
```

Este circuito **no** crea previews, no publica, no fusiona ramas y no contiene
un ejecutor de despliegue.

## Límite deliberado de integración

`delegate_task` es una herramienta en proceso de una sesión Hermes y requiere
el contexto del agente padre. No es una API pública que un script externo pueda
invocar de forma segura. Por ello el primer bridge tiene dos fases:

1. `prepare_job(...)` fija tarea, repositorio, `base_sha` y comando de
   verificación antes del despacho.
2. El Director llama a `delegate_task` y enlaza el ID devuelto con
   `bind_delegation(...)`.

Al terminar el constructor, `complete_job(...)` ignora su afirmación de que las
pruebas pasaron: inspecciona Git y vuelve a ejecutar el comando guardado. Los
supervisores se registran con `record_review(...)`; sus IDs deben ser distintos
al constructor y entre sí, y ambos veredictos se ligan al mismo SHA.

## Telegram

`TelegramDirectorBridge` acepta únicamente:

- `platform == "telegram"`;
- chat directo (`chat_type == "dm"`);
- `user_id` incluido en la allowlist del propietario;
- `chat_id == user_id` para este primer circuito DM;
- `message_id` nuevo y texto no vacío.

En ejecución real los campos provienen de `HERMES_SESSION_PLATFORM`,
`HERMES_SESSION_USER_ID`, `HERMES_SESSION_CHAT_ID` y
`HERMES_SESSION_CHAT_TYPE`, establecidos por el gateway. La allowlist se
inyecta desde configuración local; los IDs personales no se guardan en Git.

## Uso desde una sesión Director

```python
from company import Company
from phase2 import NativeDelegationBridge, TelegramDirectorBridge, TelegramEnvelope

company = Company("company.db")
director = TelegramDirectorBridge(company, allowed_owner_ids={OWNER_ID})
project_id, task_id = director.ingest(envelope, ...)

bridge = NativeDelegationBridge(company)
run_id = bridge.prepare_job(
    task_id=task_id,
    builder_role="backend",
    repository=WORKSPACE,
    base_sha=BASE_SHA,
    verification_command=[PYTHON, "-m", "unittest", "discover", "-s", "tests", "-v"],
)

# La sesión Hermes llama aquí a delegate_task(...).
bridge.bind_delegation(run_id, returned_subagent_id)

# Al recibir el resultado, el Director obtiene HEAD directamente de Git.
evidence = bridge.complete_job(run_id, returned_subagent_id, verified_head_sha)

# Dos delegate_task independientes revisan evidence["head_sha"].
bridge.record_review(run_id=run_id, reviewer="product", ...)
bridge.record_review(run_id=run_id, reviewer="architect", ...)
```

## Pruebas

```bash
cd ai-company
python -m unittest discover -s tests -v
```

`test_phase2.py` usa un repositorio Git temporal real y un comando de pruebas
real. Comprueba autenticación/replay de Telegram, cambio de commit, reejecución
de tests, vinculación al SHA e independencia de los dos revisores.

## Pendiente del siguiente hito

- Servicio/plugin duradero que abstraiga el ciclo de reentrada de resultados de
  `delegate_task` sin acoplarse a APIs internas de Hermes.
- Leases, heartbeat, cancelación y recuperación tras reinicio.
- Worktrees gestionados automáticamente y políticas por ruta/comando.
- Perfiles de modelos por rol, presupuestos y medición de costo.
- Neon y workers distribuidos.
- CI/preview por SHA; publicación continúa fuera de alcance.
