# A/B real — Un agente vs equipo de tres agentes

Misma app: gestor de gastos offline, HTML+CSS+JS sin dependencias, filtro, suma, borrado,
localStorage, validacion y vistas movil/escritorio. Misma especificacion y mismo
modelo NVIDIA `openai/gpt-oss-20b`.

| Dato | Un agente | Equipo builder + Producto + Arquitectura |
| --- | ---: | ---: |
| Generaciones/solicitudes API completadas | 1 | 3 |
| Tiempo de inferencia sumado | 79.65 s | 109.77 s |
| Tokens entrada | 441 | 4151 |
| Tokens salida | 1110 | 1675 |
| Tokens entrada + salida | 1551 | 5826 |
| Pruebas reales en Chrome | 11/11 | 11/11 |
| Correcciones necesarias | 0 | 0 |

**Resultado en este unico experimento:** Un agente necesitó 3.76 veces menos
tokens que el equipo (equivalentemente, el equipo gasto 3.76 veces los tokens)
y termino un 37.8% antes, sin peor resultado entre las 11 pruebas realizadas.
Las dos revisiones adicionales respondieron APPROVED sin requerir cambios.

**No afirmar precio en USD:** No hay factura de la API NVIDIA ni informacion sobre
tarifas aplicadas. Tokens no son una facturacion; el cociente de 3.76 se refiere
solo al conteo agregado, no necesariamente al costo monetario.

**Alcance:** roles simulados como solicitudes independientes reales a la misma
API; esto no mide sobrecarga de Git/Kanban/Telegram de los seis perfiles Hermes.
Solo una tarea y una ejecucion por metodo; no extrapolar a proyectos complejos.

**Reproducibilidad:** `identical-spec.txt`, `browser-test.js`,
`single-index.html`, `multi-index.html`, dos JSON con los 11 resultados
de Chrome y `results.json` con uso por etapa. Tests ejecutados contra Chrome
real. Capturas desktop y mobile en `C:\Dev\HermesAgentBenchmark\[single|multi]\`.

**Decision de arquitectura:** Director + constructor unico + quality gate
automatico para tareas pequenas. Incluir revisiones con IA solo cuando las
pruebas fallen o los cambios sean complejos/criticos. No eliminar los perfiles.
