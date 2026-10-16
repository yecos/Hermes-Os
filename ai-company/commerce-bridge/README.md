# Hermes Commerce Bridge — negocio intermediario automatizado

**Estado:** diseño y prototipo local separado, sin ventas ni convenios conectados.

## Modelo propuesto
El cliente describe lo que quiere comprar o contratar. Los agentes comparan productos o servicios de comercios asociados y publican recomendaciones basadas en utilidad real. El cliente completa la compra directamente con el comercio; la plataforma puede cobrar una comisión únicamente cuando la red de afiliación atribuye y aprueba la venta.

Nuestro producto es facilitar una buena decisión de compra. No vamos a mantener inventario, cobrar en nombre del comercio ni asumir entregas.

## Primer enfoque
Comenzar con servicios digitales y productos cotidianos autorizados por programas de afiliados, de forma controlada. Comparar 3–5 comercios con relación de afiliación real antes de expandir el catálogo. No existe aún un enlace de venta monetizado.

## Arquitectura
1. Catálogo: feeds/APIs autorizados con precio, territorio, disponibilidad, vencimiento y convenio aprobado.
2. Descubrimiento: investigar preguntas de compra con demanda y producir páginas de comparaciones útiles, no contenido masivo duplicado.
3. Matching: recomendar por criterios relevantes para el comprador. El importe de la comisión no controla la clasificación.
4. Conversión: redirigir a URL afiliada oficial con aviso visible de que podemos ganar comisión.
5. Atribución: registrar clics sin tratarlos como ventas; importar eventos y comisiones aprobadas de la red.
6. Control: reconcilia costos reales, conversiones y beneficios; pausa campañas deficitarias.
7. Dirección por Hermes AI Company: estados persistentes, aprobación de contratos/costos, telemetría, reportes por Telegram.

## Agentes
Director IA: estrategia y decisiones dentro del presupuesto.
Producto: investigación de necesidades y priorización.
Arquitectura: seguridad, verificación, privacidad, calidad.
Catálogo: importar y mantener ofertas válidas.
Contenido/Comercial: crear guías útiles y responder consultas autorizadas.
Operaciones: analítica, atribución, fallos y costos.

## Restricciones obligatorias
- Nada de fingir ventas, tarifas, comparativas o testimonios.
- Solo afiliaciones aprobadas y URLs oficiales; sin contratos simulados.
- Ningún agente firma convenios, crea cuentas financieras, compra anuncios, manda spam o gasta dinero sin autorización específica.
- Conservar evidencia de precio y fecha de verificación. Suspender ofertas caducadas o del país equivocado.
- Patrocinios identificados y separados de comparaciones orgánicas.
- Contabilizar como ingresos únicamente comisiones aprobadas y conciliadas; considerar devoluciones y reversos.
- Cumplir políticas de cada red y políticas de buscadores sobre contenido afiliado sin valor original.

## Estado de ejecución
Se elaboró y probó localmente un módulo Python independiente, con 9/9 pruebas superadas, que demuestra controles de ofertas, links y conversiones firmadas. El código no está conectado aún al repositorio/Runner de Hermes, a un proveedor de afiliados ni a una web pública. NO es una empresa vendiendo.

## Próximos pasos verificables
1. Crear propiedad pública demostrable (web básica con página editorial/privacidad).
2. Propietario solicita programas de afiliación, completa identidad, fiscalidad y aceptación contractual.
3. Integrar al menos un catálogo autorizado y una fuente auténtica de comisiones aprobadas.
4. Lançar 10 páginas originales sobre problemas de compra concretos; medir impresiones, clics, ventas verificadas y utilidad neta.
5. Integrar el proceso al Runner de Hermes, inicialmente con aprobación humana de publicaciones y costos.
6. Evaluar al día 30 si las ventas verificadas justifican la automatización.

**No fusionar ni desplegar como servicio comercial hasta superar las verificaciones.**
