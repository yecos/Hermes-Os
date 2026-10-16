# Validación de ofertas de afiliados

Ejecutar desde raíz \`apps/eligebien\`:

~~~bash
python3 commerce/pipeline.py commerce/partner-candidates.json --country CO
python3 -m unittest discover -s commerce/tests -v
~~~

**Resultado actual:** lista vacía. No hay asociaciones autorizadas.

Cuando el titular reciba una aprobación, registrar un \`program\` con status \`approved\`, referencia contractual, revisión humana, países y canales autorizados, fecha y dominios de redirección permitidos. Una \`offer\` debe especificar \`program_id\`, status aprobado, URL oficial afiliada y vigencia.

El script impide que un modelo publique una oferta por solo generar un enlace. No verifica por sí mismo autenticidad de contratos o resultados de la red: la verificación y conciliación deberán hacerse con el API o informe legítimo del anunciante.

Las URLs no aprobadas deben permanecer invisibles para los compradores. Las fichas editoriales originales del portal siguen siendo independientes.
