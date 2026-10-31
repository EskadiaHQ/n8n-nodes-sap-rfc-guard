#!/usr/bin/env python3
"""Builds the catalog demo workflows (Spanish names for the live classes).

Usage: build-workflows.py <output-dir> [--webhook]
Credentials are placeholders: CRED_LECTURA and CRED_T422 are replaced at import time.
"""
import json
import sys
import uuid

NODE = "n8n-nodes-sap-rfc-guard.sapRfcGuard"
WEBHOOK = "--webhook" in sys.argv
OUT = sys.argv[1]


def credential(key):
    names = {"CRED_LECTURA": "C415 · Catálogo RFC lectura (prueba aislada)",
             "CRED_T422": "C415 · Catálogo RFC T422 (prueba aislada)"}
    return {"sapRfcGuardApi": {"id": key, "name": names[key]}}


def node(name, type_, position, parameters, version=1, **extra):
    data = {"parameters": parameters, "id": str(uuid.uuid5(uuid.NAMESPACE_URL, name)), "name": name,
            "type": type_, "typeVersion": version, "position": position}
    data.update(extra)
    return data


def catalog(name, position, function, title, parameters_expr=None, operation="execute", cred="CRED_LECTURA",
            fail_on_error=True, limit=20, dry_run=False, on_error=None):
    params = {"resource": "approvedFunction", "operation": operation,
              "catalogFunction": {"__rl": True, "mode": "list", "value": function,
                                  "cachedResultName": f"{function} · {title}"}}
    if operation == "execute":
        params.update({"catalogParametersJson": parameters_expr or "{}", "limit": limit,
                       "catalogDryRun": dry_run, "catalogFailOnSapError": fail_on_error})
    extra = {"credentials": credential(cred)}
    if on_error:
        extra["onError"] = on_error
    return node(name, NODE, position, params, **extra)


def assign(name, value, type_="string"):
    return {"id": str(uuid.uuid5(uuid.NAMESPACE_URL, name + value)), "name": name, "value": value, "type": type_}


def set_node(name, position, assignments):
    return node(name, "n8n-nodes-base.set", position,
                {"assignments": {"assignments": assignments}, "options": {}}, version=3.4)


def sticky(name, position, width, height, content, color=7):
    return node(name, "n8n-nodes-base.stickyNote", position,
                {"content": content, "width": width, "height": height, "color": color})


def chain(names):
    return {a: {"main": [[{"node": b, "type": "main", "index": 0}]]} for a, b in zip(names, names[1:])}


PO = "Leer el pedido en SAP"
VENDOR = "Leer el proveedor del pedido"
ATP = "Comprobar disponibilidad del material"
SUMMARY = "Preparar resumen para compras"


def review_workflow():
    trigger_name = "Recibir pedido a revisar" if WEBHOOK else "Iniciar la revisión"
    trigger = (node(trigger_name, "n8n-nodes-base.webhook", [0, 300],
                    {"httpMethod": "POST", "path": "c415-catalogo-revisar-pedido", "responseMode": "lastNode",
                     "options": {}}, version=2, webhookId=str(uuid.uuid5(uuid.NAMESPACE_URL, "c415-catalogo")))
               if WEBHOOK else node(trigger_name, "n8n-nodes-base.manualTrigger", [0, 300], {}))
    pedido = "={{ $json.body?.pedido ?? '4500000008' }}" if WEBHOOK else "4500000008"
    nodes = [
        trigger,
        set_node("Datos de la solicitud", [220, 300], [
            assign("pedido", pedido),
            assign("fechaComprobacion", "={{ $now.toFormat('yyyy-MM-dd') }}"),
        ]),
        catalog(PO, [440, 300], "BAPI_PO_GETDETAIL1", "Read purchase order",
                "={{ JSON.stringify({ PURCHASEORDER: $json.pedido }) }}"),
        catalog(VENDOR, [660, 300], "BAPI_VENDOR_GETDETAIL", "Read supplier",
                "={{ JSON.stringify({ VENDORNO: $json.exports.POHEADER.VENDOR, COMPANYCODE: $json.exports.POHEADER.COMP_CODE }) }}"),
        catalog(ATP, [880, 300], "BAPI_MATERIAL_AVAILABILITY", "Check material availability",
                "={{ JSON.stringify({ MATERIAL: $('" + PO + "').item.json.tables.POITEM[0].MATERIAL, "
                "PLANT: $('" + PO + "').item.json.tables.POITEM[0].PLANT, "
                "UNIT: $('" + PO + "').item.json.tables.POITEM[0].PO_UNIT, "
                "WMDVSX: [{ REQ_DATE: $('Datos de la solicitud').item.json.fechaComprobacion, "
                "REQ_QTY: $('" + PO + "').item.json.tables.POITEM[0].QUANTITY }] }) }}"),
        set_node(SUMMARY, [1100, 300], [
            assign("pedido", "={{ $('" + PO + "').item.json.exports.POHEADER.PO_NUMBER }}"),
            assign("sociedad", "={{ $('" + PO + "').item.json.exports.POHEADER.COMP_CODE }}"),
            assign("proveedor", "={{ $('" + VENDOR + "').item.json.exports.GENERALDETAIL.VENDOR }}"),
            assign("nombreProveedor", "={{ $('" + VENDOR + "').item.json.exports.GENERALDETAIL.NAME }}"),
            assign("ciudadProveedor", "={{ $('" + VENDOR + "').item.json.exports.GENERALDETAIL.CITY + ' (' + $('" + VENDOR + "').item.json.exports.GENERALDETAIL.COUNTRY + ')' }}"),
            assign("material", "={{ $('" + PO + "').item.json.tables.POITEM[0].MATERIAL }}"),
            assign("planta", "={{ $('" + PO + "').item.json.tables.POITEM[0].PLANT }}"),
            assign("cantidadPedida", "={{ $('" + PO + "').item.json.tables.POITEM[0].QUANTITY + ' ' + $('" + PO + "').item.json.tables.POITEM[0].PO_UNIT }}"),
            assign("precioNeto", "={{ $('" + PO + "').item.json.tables.POITEM[0].NET_PRICE + ' ' + $('" + PO + "').item.json.exports.POHEADER.CURRENCY }}"),
            assign("fechaComprobacion", "={{ $('Datos de la solicitud').item.json.fechaComprobacion }}"),
            assign("disponibleEnPlanta", "={{ $json.exports.AV_QTY_PLT }}"),
            assign("confirmadoParaLaFecha", "={{ $json.tables.WMDVEX[0]?.COM_QTY ?? '0.000' }}"),
            assign("decision", "={{ ($json.exports.DIALOGFLAG === 'X' || Number($json.tables.WMDVEX[0]?.COM_QTY ?? 0) < Number($('" + PO + "').item.json.tables.POITEM[0].QUANTITY)) ? 'Sin cobertura en planta: pedir al proveedor confirmación de entrega' : 'Cubierto en planta: no hace falta reclamar' }}"),
            assign("evidencia", "={{ 'SAP ' + $json.meta.backend.systemId + '/' + $json.meta.backend.client + ' · política ' + $json.meta.policySha256.slice(0, 12) + ' · trazas ' + [$('" + PO + "').item.json.meta.correlationId, $('" + VENDOR + "').item.json.meta.correlationId, $json.meta.correlationId].join(', ') }}"),
        ]),
        sticky("Nota 1 · Caso", [-60, 20], 460, 240,
               "## 1 · El caso\nCompras quiere reclamar el pedido **4500000008** (12 ST de TG0001, 10 EUR/ST).\n"
               "Antes de llamar al proveedor revisa en SAP **quién es** y **si la planta 1010 tiene cobertura**.\n"
               "El número de pedido es la entrada; todo lo demás se lee en SAP.", 4),
        sticky("Nota 2 · Catálogo", [400, 20], 640, 240,
               "## 2 · Tres BAPI estándar desde el catálogo aprobado\nNo hay formulario a medida: el nodo ofrece solo funciones **aprobadas en la política del sidecar** "
               "y presentes en este SAP. El sidecar valida cada parámetro contra la **firma real** y vuelve a comprobar la política en cada llamada.\n"
               "Solo lectura: no hay commit; la disponibilidad descarta cualquier registro de actualización (rollback).", 5),
        sticky("Nota 3 · Decisión", [1060, 20], 360, 240,
               "## 3 · Decisión y evidencia\nCompara la cantidad pedida con lo confirmado para la fecha.\n"
               "Guarda sistema, mandante, hash de la política y las trazas de las tres llamadas.", 6),
    ]
    names = [trigger_name, "Datos de la solicitud", PO, VENDOR, ATP, SUMMARY]
    name = "C415 · Catálogo RFC · Revisar pedido antes de reclamar al proveedor"
    if WEBHOOK:
        name += " (prueba webhook)"
    return {"name": name, "nodes": nodes, "connections": chain(names), "settings": {"executionOrder": "v1"}}


def negative_workflow():
    trigger = node("Recibir prueba negativa", "n8n-nodes-base.webhook", [0, 300],
                   {"httpMethod": "POST", "path": "c415-catalogo-negativas", "responseMode": "lastNode", "options": {}},
                   version=2, webhookId=str(uuid.uuid5(uuid.NAMESPACE_URL, "c415-negativas")))
    cases = [
        catalog("N1 Función fuera de la credencial", [220, 300], "BAPI_COMPANYCODE_GETDETAIL", "Company code",
                '{"COMPANYCODEID":"1010"}', on_error="continueRegularOutput"),
        catalog("N2 Parámetro desconocido", [440, 300], "BAPI_PO_GETDETAIL1", "Read purchase order",
                '{"PURCHASE_ORDER":"4500000008"}', on_error="continueRegularOutput"),
        catalog("N3 Número en lugar de texto", [660, 300], "BAPI_VENDOR_GETDETAIL", "Read supplier",
                '{"VENDORNO":10300001}', on_error="continueRegularOutput"),
        catalog("N4 Mensaje de error de SAP", [880, 300], "BAPI_PO_GETDETAIL1", "Read purchase order",
                '{"PURCHASEORDER":"4599999999"}', on_error="continueRegularOutput"),
        catalog("N5 Excepción ABAP", [1100, 300], "STFC_EXCEPTION", "Raises EXAMPLE", "{}", on_error="continueRegularOutput"),
        catalog("N6 Firma no aprobada", [1320, 300], "BAPI_COMPANYCODE_GETDETAIL", "Company code",
                operation="getSignature", cred="CRED_T422", on_error="continueRegularOutput"),
        catalog("N7 SAP sin autorización RFC", [1540, 300], "BAPI_PO_GETDETAIL1", "Read purchase order",
                '{"PURCHASEORDER":"4500000008"}', cred="CRED_T422", on_error="continueRegularOutput"),
        catalog("P8 Validar sin llamar a SAP", [1760, 300], "BAPI_VENDOR_GETDETAIL", "Read supplier",
                '{"VENDORNO":"10300001","COMPANYCODE":"1010"}', dry_run=True, on_error="continueRegularOutput"),
        node("P9 Operación existente sin cambios", NODE, [1980, 300],
             {"resource": "purchaseOrder", "operation": "getDetails", "purchaseOrderId": "4500000008", "limit": 5},
             credentials=credential("CRED_LECTURA"), onError="continueRegularOutput"),
        catalog("P10 Firma real de la BAPI", [2200, 300], "BAPI_PO_GETDETAIL1", "Read purchase order",
                operation="getSignature", on_error="continueRegularOutput"),
        catalog("P11 Buscar en el catálogo", [2420, 300], "BAPI_PO_GETDETAIL1", "", operation="search",
                on_error="continueRegularOutput"),
    ]
    cases[-1]["parameters"] = {"resource": "approvedFunction", "operation": "search", "catalogQuery": "", "limit": 20}
    names = [trigger["name"]] + [case["name"] for case in cases]
    summary = set_node("Resumen de las pruebas", [2640, 300], [
        assign(case["name"], "={{ JSON.stringify($('" + case["name"] + "').all().map(item => item.json.error ?? item.json.outcome ?? item.json.name ?? Object.keys(item.json).slice(0, 4).join('|'))) }}")
        for case in cases])
    return {"name": "C415 · Catálogo RFC · Pruebas negativas (webhook)", "nodes": [trigger, *cases, summary],
            "connections": chain(names + [summary["name"]]), "settings": {"executionOrder": "v1"}}


workflows = [review_workflow()]
if WEBHOOK:
    workflows.append(negative_workflow())
for workflow in workflows:
    slug = "revisar-pedido" + ("-webhook" if WEBHOOK else "") if "Revisar" in workflow["name"] else "pruebas-negativas"
    with open(f"{OUT}/c415-catalogo-{slug}.json", "w", encoding="utf-8") as handle:
        json.dump(workflow, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print(f"{OUT}/c415-catalogo-{slug}.json")
