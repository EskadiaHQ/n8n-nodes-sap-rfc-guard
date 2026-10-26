# Salud y presupuesto de memoria — 23/10/2026

Las nuevas compilaciones usan `scripts/healthcheck.sh` con curl sobre loopback,
autenticación Bearer y el endpoint SAP existente. Conserva ping y comprobación de
dependencia, exige HTTP 200, no sigue redirecciones, no reintenta y termina en
cuatro segundos. No arranca otra JVM cada veinte segundos. El token viaja por
stdin hacia curl, nunca en sus argumentos ni en la salida del probe.

Los dos aprovisionadores arrancan Java con `-Xms32m -Xmx192m`, definido en sus
derivados runtime y ejemplos Compose. Este presupuesto no se impone a otros
modos del Dockerfile compartido. El límite del contenedor es 768 MiB y el
presupuesto total de RAM más swap, 1 GiB. El heap máximo no es el consumo total:
el margen restante cubre JCo nativo, hilos, clases, código compilado y procesos
auxiliares. No se cambia pooling ni transacciones. Si un despliegue define
`JAVA_TOOL_OPTIONS`, debe conservar estos límites explícitos.

En el ensayo aislado de las imágenes originales, Java 21.0.7 calculó un heap
inicial de unos 968 MiB y máximo de 15,1 GiB pese al límite Docker de 384 MiB.
Es evidencia de un presupuesto automático inadecuado en ese entorno, sin
demostrar una fuga ni atribuir todos los OOM a una única causa.

La corrección productiva se construye sobre cada imagen exacta existente y
copia solo el probe; no recompila sus JAR ni sustituye una versión por la otra.
Los ejemplos Compose exigen una imagen construida con este Dockerfile o su
derivado runtime validado. Sus etiquetas de ejemplo no son instrucciones para
reemplazar las versiones productivas.

Pruebas del probe: `python3 scripts/test_healthcheck.py`. La aceptación de cada
despliegue incluye salud autenticada positiva y negativa, timeout, carga de
lecturas, RAM/picos, cero OOM/reinicios, arranque y reversión. Las escrituras SAP
no se repiten tras un resultado incierto: el reintento pertenece al cliente y
solo procede si puede probar que no envió la petición. El monitor de fallos
finales vive en `servers/apps/sap-provisioner-ops`.
