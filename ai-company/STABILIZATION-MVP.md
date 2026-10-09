# Hermes AI Company — MVP de estabilizacion

Objetivo: producir entregas pequenas verificables con un solo Director via Telegram,
un constructor por proyecto y dos revisores independientes. Conservar los seis
perfiles existentes; NO sumar agentes, paneles, gateways ni proveedores de IA.

## Camino corto

1. Telegram/Director registra una especificacion acotada y un repo Git limpio:
   `company_control.py submit` => `pending_approval`. No consume modelos.
2. Una aprobacion humana autoriza `approve JOB_ID`. El controlador exige **tres
   inicios disponibles ese dia UTC** y **ningun otro proyecto activo**.
3. Un constructor crea un commit temprano (objetivo: antes del turno 6), informa
   HEAD de 40 caracteres y mantiene su worktree limpio.
4. `quality_gate.py` verifica SHA real y descendencia, diff, archivos sensibles,
   cambios sin registrar y, si hay tests Python stdlib modificados, ejecuta
   `python -B -m unittest discover -s tests -v` (45 s max, sin instalar paquetes).
   Ignora UNICAMENTE bytecode Python NO rastreado dentro de `__pycache__`.
   Codigo fuente modificado, credenciales y tests fallidos bloquean el proyecto.
   Para otros lenguajes, la puerta es Git/diff; NO declara tests que no corrieron.
5. Producto revisa diff + paquete determinista en modo lectura y emite
   `APPROVED:<SHA>` o `REJECTED:<SHA>`. Arquitectura repite de forma
   independiente sobre EL MISMO SHA. Las aprobaciones genericas no valen.
6. `awaiting_owner` no hace merge/deploy; el propietario decide `accept`.

El controlador y la puerta de calidad NO llaman modelos. Los tests de codigo
generado se ejecutan localmente y pueden contener codigo arbitrario: usar
un entorno de desarrollo autorizado, aislado de produccion y sin secretos.

## Limites que permanecen activos

- Provider: openai-codex, exactamente modelos definidos en `eco-policy.json`.
- Inicios por UTC dia: cuatro globales; normalmente tres por proyecto (constructor
  y dos supervisores), uno por rol/proyecto. Segundo constructor solo con
  autorizacion humana, una vez y sin alterar el limite global.
- Los reviewers mantienen 8 iteraciones; no subirlas silenciosamente.
- Sin delegacion recursiva ni Kanban gateway autodispatch. `tick` es el
  unico despachador autorizado.
- Si hay 429, error de revision, fallo de tests o limites agotados: bloquear,
  conservar evidencia y NO reintentar automaticamente.
- Director via Telegram no forma parte del contador local de arranques
  Kanban. Los tokens con cache NO son cuotas ni creditos del plan.

## Validacion del MVP: tres proyectos reales, consecutivos

Los repos de prueba estan aislados en `C:\Dev\HermesCompanyPilots\` y
sus jobs quedan inicialmente `pending_approval`. NINGUNO se lanza de oficio.

| Orden | Proyecto | Constructor | Prueba de salida |
| --- | --- | --- | --- |
| 1 | Resumen CSV de gastos | Backend | 3+ tests, SHA, 2 revisiones |
| 2 | Lista de tareas offline | Frontend | UI funcional, prueba de estructura, SHA, 2 revisiones |
| 3 | Handler de webhook JSON | Integraciones | 3+ tests, SHA, 2 revisiones |

Se ejecutan UNO POR UNO con autorizacion humana separada y presupuesto
disponible. No se deben anunciar como exitosos antes de un recorrido
real completo. Metas: 3/3 culminados sin intervencion tecnica manual,
exactamente 1 builder + 2 reviewers por piloto, sin cambios no
autorizados, sin errores del controlador, sin 429 y pruebas pertinentes PASS.

Si falla un piloto, conservar estado, registrar causa, corregir el controlador
sin lanzar IA y volver a medir. No alterar automaticamente los modelos o
los topes de sesiones.

## Comandos: cero llamadas de modelo

```powershell
cd C:\Dev\Hermes-AI-Company-ECO
python ai-company\company_control.py metrics
python ai-company\company_control.py list
python -m unittest discover -s ai-company/tests -q
hermes kanban --board hermes-ai-company stats
```

Probar sin modelos la puerta de calidad de la entrega piloto original:

```powershell
python ai-company\quality_gate.py --workspace C:\Dev\HermesCompanyPilot\.worktrees\t_6dcf2e59 --base c5ae6600f45cd1c4122c55604729d51c2e5eaa6a --head e677b8b4f9dc1154a5e26f4a9fd2de9ef293aa63
```

La suite de test Python crea tres repos Git temporales y ejecuta su puerta
de calidad: NO es una simulacion del consumo, conexion o respuestas de
seis agentes reales. El primer piloto de Codex encontro un bloqueo real
de Arquitectura (8/8): sigue `blocked`, no aceptado ni desplegado.

## Definicion de exito

No basta que 50+ pruebas unitarias pasen. Se requiere observar en
Kanban nativo tres proyectos distintos desde autorizacion, constructor
y SHA, puerta sin IA, Producto, Arquitectura y `awaiting_owner`.
Medir inicios, fallos, excepciones, sesiones y tiempo, no inferir
consumo facturado del total de tokens.
