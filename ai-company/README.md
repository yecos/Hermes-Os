# Hermes AI Company — primera entrega

Módulo experimental aislado dentro de Hermes OS. El propietario utilizará **una sola conversación con el Director en Telegram**, por medio del gateway de mensajería que ya incorpora Hermes Agent; **no crearemos otro bot**.

## Estado actual

**Implementado y probado localmente:** motor de proyectos, tareas, bitácora de eventos, evidencia obligatoria, revisión funcional y técnica por separado, escalamiento por errores y solicitudes de aprobación ligadas al SHA exacto de un commit. Usa SQLite únicamente para este prototipo inicial.

**Pendiente:** conexión real con Telegram/Hermes Gateway, ejecución de los seis agentes y sus modelos, Neon, Git worktrees, CI, Vercel y despliegue. **Nada de esto está conectado o ejecutándose todavía.** La aprobación del prototipo nunca publica código.

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
