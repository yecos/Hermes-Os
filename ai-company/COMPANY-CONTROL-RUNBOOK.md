# Hermes AI Company — controlador persistente ECO (Windows)

Este controlador usa el **Kanban nativo Hermes Agent 0.20.2**, seis perfiles independientes y un **solo gateway de Telegram**. No modifica el núcleo de Hermes Agent. No genera llamadas a modelos cuando la cola está vacía o los proyectos no tienen aprobación.

## Estado e instalación

Archivos: `company_control.py`, `eco_mode.py`, `eco-policy.json`, `eco-loop-windows.ps1`, y pruebas en `tests/`.

Ubicaciones del PC:
- Código: `C:\Dev\Hermes-AI-Company-ECO\ai-company`
- Datos persistentes: `%LOCALAPPDATA%\hermes\company-control\` (fuera del repo)
- Kanban nativo: `%LOCALAPPDATA%\hermes\kanban\boards\hermes-ai-company\kanban.db`
- Tarea Windows: `Hermes-AI-Company-ECO`; inicia al entrar en Windows y tiene disparador diario de recuperación
- Gateway Telegram: solo perfil `default`, sin gateways secundarios

**Seguridad operativa indispensable:** `hermes config get kanban.dispatch_in_gateway` debe ser `false`. De otro modo, el controlador rechaza el despacho, pues otro proceso podría lanzar sesiones sin reservar presupuesto. El supervisor del controlador ejecuta `tick` cada 45 segundos. No se debe usar `hermes kanban dispatch` manualmente en `hermes-ai-company` ni cambiar los overrides de modelos o el presupuesto.

## Política

`openai-codex` exclusivamente. Director `gpt-5.6-sol` (12 turnos), Producto/Arquitectura `gpt-6-luna` (8 turnos), constructores `gpt-5.6-luna` (12 turnos). Solo un trabajo Kanban simultáneo.

Presupuesto de lanzamiento del **controlador**: 4 sesiones diarias globales, 3 por proyecto, 1 por rol/proyecto (UTC). La conversación del Director por Telegram **no** se incluye en ese contador; tiene límite nativo de 12 iteraciones por turno. La cuota real de ChatGPT no puede calcularse únicamente a partir de tokens.

## Flujo

1. `submit` registra un brief y referencia al SHA base en un repositorio Git **limpio**. No lanza IA. Estado `pending_approval`.
2. `approve` debe realizarse solo tras autorización explícita del propietario en el chat autenticado. Pasa a `queued`. **Es el punto que permite gastar Codex**.
3. `tick` crea una tarea con ID idempotente en Kanban para el perfil constructor, reserva su presupuesto SQLite y llama al despachador nativo. Tiene que obtener un commit Git real, limpio y descendiente del SHA base.
4. Los supervisores Producto y Arquitectura trabajan **por separado**, en ese orden por ahorro, sobre el **mismo SHA**; cada uno debe emitir `APPROVED:<SHA>` o `REJECTED:<SHA>`, sin acceso a una aprobación genérica.
5. Solo si ambos recibos pasan queda `awaiting_owner`. `accept` registra la aceptación humana. Nunca hace merge, preview ni deploy.

No ejecutar `--goal`, auto-decompose o delegaciones recursivas. Nunca sustituir silenciosamente el modelo en caso de HTTP 429. La recuperación conserva las reservas inciertas y bloquea cualquier duplicado hasta reconciliar el PID / recibo de Kanban.

## Comandos sin IA

```powershell
cd C:\Dev\Hermes-AI-Company-ECO
python ai-company\company_control.py list
python ai-company\company_control.py tick
python ai-company\company_control.py status <JOB_ID>
hermes kanban --board hermes-ai-company stats
python -m unittest discover -s ai-company/tests -q
```

Para **crear un proyecto pendiente sin gastar IA**:

```powershell
python ai-company\company_control.py submit --title "Tarea piloto" --spec "Crear una función y su prueba, un solo commit, sin despliegue" --repo "C:\Dev\HermesCompanyPilot" --role backend
```

Luego, **solo con autorización del propietario**:

```powershell
python ai-company\company_control.py approve <JOB_ID>
```

Consultar `status <JOB_ID>` y recibir los resultados de las tres sesiones. Con dos revisiones verificadas, `accept <JOB_ID>` solo tras autorización explícita. No lanzar prueba de modelos sin aprobación del usuario.

## Interfaz única Telegram

El Director del perfil `default` es el único que conversa con el propietario. Para pedidos explícitos de Hermes AI Company, ejecuta los comandos del controlador mediante la herramienta de terminal, devuelve el ID del proyecto y solicita aprobación. **No use directamente** `delegate_task`, `hermes kanban create` ni `kanban dispatch` para evitar saltarse los presupuestos. Telegram mantiene deshabilitado el toolset `delegation` por defecto.

## Verificación y límites conocidos

Sin iniciar modelos, pruebas locales `41/41` y comprobación `tick` con cola vacía: `no billable work`. El supervisor Windows registró `CHECK_OK`.

**Pendiente para declarar E2E terminado:** demostrar 1 ejecución real de constructor + 2 revisores, comprobar los modelos efectivos en las sesiones, trabajo Git, consumo posterior e informar al propietario. Los controles de tokens `max_output_tokens` declarados en la política no están aplicados por Kanban nativo; los presupuestos que se hacen cumplir son número de arranques, concurrencia, max_turns y tiempos máximos. Los logs de Hermes/uso no equivalen a facturación de suscripción.

No publicar bases de datos, credenciales, logs de sesiones ni archivos `.env`. Mantener PR #10 en borrador hasta revisión humana.

## MVP de estabilizacion y puerta sin IA
Consultar [STABILIZATION-MVP.md](STABILIZATION-MVP.md). El constructor prioriza commit temprano; quality_gate verifica Git, detecta secretos en el diff y corre unittest Python stdlib cuando hay tests cambiados (sin gastar IA). El comando `company_control.py metrics` es local. Una tarea no se aprueba si faltan los 3 inicios para constructor + dos revisores o hay otra tarea activa. Se prepararon tres pilotos reales pendientes de aprobacion humana; nada se inicia automaticamente.
