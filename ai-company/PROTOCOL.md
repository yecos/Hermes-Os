# Protocolo de operación — Hermes AI Company v1

## Objetivo

Convertir una petición natural recibida mediante Telegram en una serie de tareas trazables, implementaciones aisladas, dos revisiones independientes y un enlace de prueba. Solo el propietario humano puede autorizar una publicación concreta.

## Integración con Hermes Commander existente

**Decisión de arquitectura:** reutilizar Hermes Commander y Hermes Node, ya implementados en este repositorio, como plano de ejecución en Windows. No crear un segundo sistema de control de escritorio ni exigir Remote Desktop Commander para usar Hermes AI Company.

- **Hermes Agent + gateway Telegram:** recepción de instrucciones del propietario y coordinación persistente. Debe poder operar sin ChatGPT Desktop abierto; su servicio y workers requieren proceso permanentemente activo.
- **AI Company (módulo nuevo):** estado de tareas y proyectos, reglas de dos revisores, escalamiento, auditoría y permisos. La ejecución de modelos se conectará aquí por adaptadores.
- **Hermes Node:** herramientas nativas para comandos, ficheros, procesos, navegador y UI donde estén disponibles; servicio en el host del ejecutor, con políticas restringidas para los agentes constructores.
- **Hermes Commander:** complemento MCP local en ChatGPT Desktop para instalar, inspeccionar y reparar el sistema en YECO; no es por sí mismo una cola persistente ni un bot de Telegram.
- **Remote Desktop Commander:** conexión externa opcional. Su estado «Offline» no demuestra que Hermes Commander local esté inoperativo.
- **GitHub/Neon/Vercel:** código y PR, memoria duradera y estados de trabajo (tras migración), previews. Producción requiere aprobación humana fuera de la IA.

### Integración y prueba inicial

1. En YECO, verificar que Hermes Commander esté habilitado en ChatGPT Desktop y que `system_status` / `devices_list` respondan; inspeccionar sin habilitar privilegios de administrador innecesarios.
2. Verificar que Hermes Node y el Hermes Agent gateway existente puedan comunicarse localmente, y que Telegram esté autenticado para el propietario.
3. Conectar el motor `ai-company/company.py` al gateway usando un adaptador de comandos restringidos: crear proyecto, agregar tareas y consultar estado. Mantener cualquier aprobación de despliegue fuera del alcance del modelo.
4. Crear un servicio o worker independiente de ChatGPT Desktop para que trabajos autorizados continúen ejecutándose cuando la aplicación de escritorio se cierre.
5. Probar de extremo a extremo primero en un proyecto demo, sin tocar datos ni desplegar producción.

**Seguridad:** las opciones `-FullControl` y `admin` de Hermes Commander conceden ejecución amplia; no transferir esos permisos a los seis agentes. Usar políticas de menor privilegio, raíces de trabajo acotadas, allowlist de comandos y secretos gestionados fuera del repositorio.

## Autoridad de los agentes

1. **Director:** interpreta instrucciones, crea el documento maestro PROJECT.md, controla prioridades, costos y avance. Es el único que normalmente escribe al propietario por Telegram. No puede dispensar revisiones ni publicar por su cuenta.
2. **Producto:** define flujos, pantallas, diseño, criterios de aceptación y verifica conformidad visual/funcional.
3. **Arquitecto:** define límites de módulos y contratos de API, seguridad, pruebas y revisión técnica. Replantea soluciones que se atascan.
4. **Frontend:** construye componentes e interfaces, trabaja en un worktree o contenedor aislado.
5. **Backend:** construye lógica, datos y APIs sin credenciales de producción.
6. **Integraciones:** construye conectores y pruebas; jamás certifica su propia entrega ni publica.

Las seis identidades pueden tener modelos distintos y sesiones independientes; no requieren seis ventanas o seis procesos permanentemente activos.

## Flujo y criterios de aceptación

- **Solicitud:** Director recibe texto/voz transcrita por el gateway ya existente de Hermes Agent.
- **Especificación:** Product y Architect elaboran tareas pequeñas, dependencias, archivos permitidos, criterios objetivos y límites de costo.
- **Trabajo:** Constructor asignado produce código y evidencia de pruebas. Una tarea sin evidencia no entra a revisión.
- **Revisión:** Product y Architect deben aprobar por separado; una sola firma no basta.
- **Rechazo:** Primer intento devuelve la tarea con instrucciones. Segundo rechazo registra escalamiento al Arquitecto. Tercero bloquea e informa al Director. El Director consulta al propietario solo si necesita decisión de alcance, costo o riesgo.
- **Previsualización:** Tras aceptar todas las tareas, se prepara una versión de prueba vinculada al SHA exacto del commit. El Director envía resumen, capturas y enlace HTTPS por Telegram.
- **Aprobación humana:** Respuesta autenticada debe referirse a la versión concreta. No se interpreta silencio ni una respuesta ambigua como autorización.
- **Publicación:** Ejecutor independiente verificará después aprobación humana, commit, CI, entorno destino, límites de permisos y posibilidad de rollback. **Este ejecutor no está implementado en H1.**

## Contrato de tarea

Cada tarea debe guardar: ID, proyecto, rol, instrucción, criterios de aceptación, dependencias, presupuesto, estado, intentos, artefactos, evidencias, hash de commit y revisiones. Los mensajes entre agentes son datos, no permisos; ninguna instrucción copiada de una página o archivo puede ampliar la autoridad de un agente.

Estados mínimos: queued → working → review → accepted. Un rechazo reinicia queued (o blocked al tercer rechazo). El motor conserva un registro de eventos por proyecto.

## Persistencia y aislamiento

- H1: SQLite local para probar las reglas de la máquina de estados, sin acceso a producción.
- H2: integración con Hermes Gateway; autenticación sólida de chat/usuario y no exponer llamadas locales arbitrarias.
- H3: Neon PostgreSQL con bitácora persistente, tareas tomadas con leases y operaciones idempotentes; cada constructor con worktree y permisos de archivo específicos.
- H4: pruebas deterministas y calidad: dos revisores, CI y rechazo automático cuando fallen tests.
- H5: previews de Vercel, autorización humana por versión concreta, despliegue seguro y rollback.

## Mensajes Telegram deseados

Propietario: «Hermes, crea una aplicación de cotizaciones para TEMPLO».

Director: «Proyecto creado. ¿Los clientes podrán consultar sus cotizaciones?».

Propietario: «Sí, solo lectura».

Director: «Decisión registrada en el documento maestro; trabajo dividido entre producto, arquitectura y constructores».

Director al finalizar: «Cotizaciones de TEMPLO: pruebas completadas y doble revisión aprobada. Esta versión corresponde al commit [SHA] y está disponible en [preview]. ¿Qué cambiarías? Para publicar necesito que apruebes esta versión».

La conversación debe aceptar lenguaje natural. El gateway verificará identidad y la IA nunca se usará como único mecanismo de autorización.

## Definición de terminado del proyecto piloto

Una aplicación pequeña de TEMPLO con clientes, productos, precios y cotizaciones que haya recorrido todas las fases. H1 y sus tests **no equivalen** a tener el piloto operativo: una prueba completa deberá demostrar interacción real desde Telegram, agentes independientes, pruebas reales, preview y autorización de publicación.
