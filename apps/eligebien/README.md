# EligeBien — MVP de portal de intermediación

Portal editorial para comparar herramientas por necesidad cotidiana. **No es todavía una empresa que venda ni una aplicación con agentes de venta conectados.**

## Funciona actualmente
- Buscador y filtros interactivos de 15 proveedores conocidos.
- Comparaciones breves con criterios de adecuación y precauciones.
- Contenido explicativo y avisos de privacidad/comercialización.
- Enlaces directos oficiales **sin afiliación**.
- API de métricas anónimas opcional con Neon: categoría, acción, identificador de oferta y fecha. No almacena las consultas.

## Ejecución
Abrir \`index.html\` directamente o servir esta carpeta con un servidor estático.
Vercel: proyecto en raíz \`apps/eligebien\`, framework **Other**, Node.js API \`api/event.js\`. Configurar \`DATABASE_URL\` como variable cifrada desde Neon (nunca ponerla en Git). Sin esa variable la búsqueda funciona, pero los eventos no se almacenan.

Esquema SQL de la tabla:

CREATE TABLE IF NOT EXISTS public.engagement_events (
 id BIGSERIAL PRIMARY KEY,
 event_type TEXT NOT NULL CHECK (event_type IN ('category_view','recommend','official_click')),
 category TEXT NOT NULL DEFAULT 'general',
 offer_id TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

## Camino a ingresos verificables
1. Publicar URL y verificar propiedad del sitio.
2. Registrarse en redes de afiliados; propietario completa acuerdos, identidad y fiscalidad.
3. Solicitar y confirmar aprobación de cada programa, país y canal de promoción.
4. Importar ofertas autorizadas, feeds y enlaces verificados, sin inventar precios ni patrocinios.
5. Conectar tracking y conciliación firmados; contabilizar solo comisiones aprobadas, menos reversos.
6. Integrar un Runner real de Hermes con permisos, controles de presupuesto y reportes Telegram.
7. Crear contenido genuino, adquirir tráfico legítimo y medir ingresos reales.

## No implementado
La IA de seis agentes aún no ejecuta transacciones; no hay convenios, pagos, métricas conectadas a producción ni ingresos. No publicar términos de afiliación falsos. Para actualizar catálogo, revisar calidad y cumplimiento.

## Seguridad
El texto de las búsquedas nunca se envía a base de datos. No existe registro de usuarios, captura de correo ni cobro. El endpoint valida tipo y categoría e impone un tope diario global. Antes de un lanzamiento con tráfico amplio se requiere endurecimiento antiabuso y monitorización.
