# Hermes Commander completo en Chat normal y móvil

**Estado:** prototipo de transporte preparado, no conectado a ninguna cuenta de OpenAI ni probado en un Chat móvil. La limitación de cuenta/superficie de ChatGPT se verifica al conectar el plugin. No se modifican permisos ni se reinstala el Hermes local.

## Diagnóstico confirmado en el repositorio

La instalación existente usa `plugins/hermes-commander/.mcp.json` con un comando Windows, `plugins/hermes-commander/scripts/launch-windows.ps1`, para iniciar `hermes-node.exe mcp` mediante STDIO. **Esto es un plugin MCP local de escritorio:** no se vuelve móvil/web por instalarlo en otra máquina o por usar las mismas credenciales.

El binario Hermes MCP ya expone comandos, archivos, procesos, screenshots, UI Automation, mouse/teclado, Chrome y selección de dispositivos. Esos permisos se gobiernan por `%USERPROFILE%\.hermes-node\hermes-node.env` y las aprobaciones de ChatGPT. **No crear una versión de solo lectura ni recortar su lista de herramientas.**

## Ruta seleccionada

```text
ChatGPT Chat (web; móvil si la cuenta y superficie soportan el plugin)
  -> plugin MCP remoto vinculado a la cuenta
     -> OpenAI Secure MCP Tunnel (tunnel_id)
        -> tunnel-client Windows (HTTPS saliente)
           -> PowerShell launcher Hermes existente
              -> hermes-node.exe mcp (full tools)
                 -> Windows local / otros nodos Hermes por red privada
```

**La conexión por túnel NO elimina las restricciones de plan, modo Chat, voz o permisos de la plataforma.** ChatGPT puede requerir un plan o workspace elegible y rechazar herramientas de escritura. En ese caso hay que usar un modo compatible o un cliente independiente; no se puede habilitar por cambio de código local.

La ruta evita publicar el puerto 9090, abrir el firewall o convertir la API HTTP de Hermes en un endpoint público.

## Preparación cuando se tenga acceso a Windows

1. Verificar que Hermes ya funciona en Desktop y que existen `%LOCALAPPDATA%\HermesCommander\hermes-node.exe` y `%USERPROFILE%\.hermes-node\hermes-node.env`.
2. En **OpenAI Platform → Tunnels**, crear un túnel y obtener el `tunnel_id`, un **runtime API key** y permisos `Tunnels Read + Use`. No confundirlos con el login de ChatGPT; Platform y el workspace ChatGPT son ámbitos separados.
3. Instalar el **tunnel-client oficial** desde Platform Tunnels o https://github.com/openai/tunnel-client/releases/latest. Comprobar `tunnel-client help quickstart`.
4. Desde una copia de esta rama de Hermes-Os, ejecutar un diagnóstico sin llamadas de red:
   ```powershell
   powershell -NoProfile -File .\scripts\connect-hermes-commander-chat-windows.ps1 -TunnelId tunnel_TU_ID -DryRun
   ```
5. Definir la clave de ejecución **solo en la sesión local de PowerShell**. No guardarla en el repositorio, chats, scripts ni capturas:
   ```powershell
   $env:CONTROL_PLANE_API_KEY = Read-Host "Clave de ejecución del túnel"
   powershell -NoProfile -File .\scripts\connect-hermes-commander-chat-windows.ps1 -TunnelId tunnel_TU_ID -Run
   ```
   El script construye un perfil `hermes-commander-full-chat`, llama `tunnel-client init`, `doctor`, y `run`. Usa el lanzador original de Hermes y no toca `hermes-node.env`. Mantener el proceso/túnel encendido para que ChatGPT alcance el computador.
6. En **ChatGPT web → Plugins → + → Add custom MCP server → Tunnel**, seleccionar el túnel y crear el plugin **Hermes Commander (remoto)**. Completar la autorización y permitir las herramientas disponibles según la cuenta. El plugin **local** anterior puede mantenerse para Desktop.
7. Abrir un **Chat de texto** (no Work), seleccionar el plugin con `@` y ejecutar primero `system_status`, luego una lectura y finalmente una acción inocua de escritura con aprobación. Probar iPhone después. **Las conversaciones de voz pueden tener otra matriz de compatibilidad.**

## Condiciones para darlo por logrado

- Chat web ve al menos `devices_list`, `execute_command`, `write_file`, `screenshot`, `mouse_click`.
- `system_status` responde desde Windows y puede seleccionar un nodo Hermes remoto previamente registrado.
- Ejecución de prueba `git --version`, luego crear/leer un archivo de prueba con la política de permisos existente y la autorización del usuario.
- Repetir desde **Chat del iPhone**. Si el plugin no está disponible en esa superficie, marcarlo como bloqueo de plataforma, sin afirmar éxito.
- No se publican tokens ni el puerto local, y no se sobrescribe configuración previa.
- Antes de acciones críticas, mantener confirmaciones de ChatGPT y guardas de Hermes; conservar registros.

## Riesgos y compatibilidad

- Las **herramientas completas** dan control potente sobre el PC. Conectarlas a ChatGPT supone transmitir datos de las acciones que se invoquen y riesgo frente a instrucciones maliciosas. Mantener aprobación para cambios críticos incluso con acceso de administrador.
- El túnel requiere un `tunnel_id` y runtime API key; la disponibilidad y facturación se rigen por los productos y permisos de OpenAI, no por el instalador de Hermes.
- Un plugin importado que declara `.mcp.json` puede seguir marcado **Desktop only**; **el nuevo plugin remoto se crea separado mediante la opción Tunnel**, no editando su `.mcp.json` para ponerle una URL.
- Sin un ordenador en línea y un túnel activo no hay sesión remota que ejecutar.

Documentación oficial revisada:
- https://help.openai.com/en/articles/20001256-plugins-in-chatgpt
- https://developers.openai.com/api/docs/guides/custom-mcp-server
- https://developers.openai.com/api/docs/guides/secure-mcp-tunnels
- https://developers.openai.com/plugins/deploy/connect-chatgpt
