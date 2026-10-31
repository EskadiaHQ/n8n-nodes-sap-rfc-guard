"""Builds the negative/positive direct-sidecar test matrix as shell lines for logali-prod."""
import json, shlex

def body(function, params=None, readonly=True, options=None, raw=None):
    payload = {"function": function}
    if readonly is not None:
        payload["context"] = {"readOnly": readonly}
    if raw is not None:
        payload["parametersJson"] = raw
    elif params is not None:
        payload["parametersJson"] = json.dumps(params)
    if options:
        payload["options"] = options
    return json.dumps(payload)

T = "c415-rfc-catalog-test"
R = "c415-rfc-catalog-t422"
ex = lambda f: f"/v1/catalog/functions/{f}/execute"
cases = [
    ("N01 function outside policy", T, "POST", ex("BAPI_CUSTOMER_GETDETAIL2"), body("BAPI_CUSTOMER_GETDETAIL2", {"CUSTOMERNO": "0017100001"}), "read-only"),
    ("N02 generic executor blocked", T, "POST", ex("RFC_READ_TABLE"), body("RFC_READ_TABLE", {"QUERY_TABLE": "USR02"}), "read-only"),
    ("N03 listed but execution not approved", T, "POST", ex("BAPI_SALESORDER_GETSTATUS"), body("BAPI_SALESORDER_GETSTATUS", {"SALESDOCUMENT": "0000000512"}), "read-only"),
    ("N04 signature not approved by policy", T, "GET", "/v1/catalog/functions/BAPI_COMPANYCODE_GETDETAIL", "", ""),
    ("P04 same function executes (execute approved)", T, "POST", ex("BAPI_COMPANYCODE_GETDETAIL"), body("BAPI_COMPANYCODE_GETDETAIL", {"COMPANYCODEID": "1010"}), "read-only"),
    ("N05 approved but absent from SAP", T, "GET", "/v1/catalog/functions/Z_C415_NOT_IN_SAP", "", ""),
    ("N06 unknown parameter", T, "POST", ex("BAPI_PO_GETDETAIL1"), body("BAPI_PO_GETDETAIL1", {"PURCHASE_ORDER": "4500000008"}), "read-only"),
    ("N07 number instead of text (leading zeros)", T, "POST", ex("BAPI_VENDOR_GETDETAIL"), body("BAPI_VENDOR_GETDETAIL", raw='{"VENDORNO": 10300001}'), "read-only"),
    ("N08 parameter fixed by policy", T, "POST", ex("BAPI_VENDOR_GETDETAIL"), body("BAPI_VENDOR_GETDETAIL", {"VENDORNO": "0010300001", "BYPASS_AUTH_CHECK": "X"}), "read-only"),
    ("N09 parameter in SAP but not approved", T, "POST", ex("BAPI_PO_GETDETAIL1"), body("BAPI_PO_GETDETAIL1", {"PURCHASEORDER": "4500000008", "SERVICES": "X"}), "read-only"),
    ("N10 required parameter missing", T, "POST", ex("BAPI_PO_GETDETAIL1"), body("BAPI_PO_GETDETAIL1", {}), "read-only"),
    ("N11 unknown field in nested table", T, "POST", ex("BAPI_MATERIAL_AVAILABILITY"), body("BAPI_MATERIAL_AVAILABILITY", {"MATERIAL": "TG11", "PLANT": "1010", "UNIT": "ST", "WMDVSX": [{"REQ_DATE": "2026-11-02", "REQ_QUANTITY": "1"}]}), "read-only"),
    ("N12 field not approved in nested table", T, "POST", ex("BAPI_MATERIAL_AVAILABILITY"), body("BAPI_MATERIAL_AVAILABILITY", {"MATERIAL": "TG11", "PLANT": "1010", "UNIT": "ST", "WMDVSX": [{"REQ_DATE": "2026-11-02", "REQ_QTY": "1", "DELKZ": "BB"}]}), "read-only"),
    ("N13 decimal would be rounded", T, "POST", ex("BAPI_MATERIAL_AVAILABILITY"), body("BAPI_MATERIAL_AVAILABILITY", {"MATERIAL": "TG11", "PLANT": "1010", "UNIT": "ST", "WMDVSX": [{"REQ_DATE": "2026-11-02", "REQ_QTY": "1.2345"}]}), "read-only"),
    ("N14 invalid date", T, "POST", ex("BAPI_MATERIAL_AVAILABILITY"), body("BAPI_MATERIAL_AVAILABILITY", {"MATERIAL": "TG11", "PLANT": "1010", "UNIT": "ST", "WMDVSX": [{"REQ_DATE": "02.11.2026", "REQ_QTY": "1"}]}), "read-only"),
    ("N15 EXPORT parameter sent as input", T, "POST", ex("BAPI_PO_GETDETAIL1"), body("BAPI_PO_GETDETAIL1", {"PURCHASEORDER": "4500000008", "POHEADER": {}}), "read-only"),
    ("N16 value outside policy pattern", T, "POST", ex("BAPI_PO_GETDETAIL1"), body("BAPI_PO_GETDETAIL1", {"PURCHASEORDER": "45000ABC"}), "read-only"),
    ("N17 bypass: no read-only contract", T, "POST", ex("BAPI_PO_GETDETAIL1"), body("BAPI_PO_GETDETAIL1", {"PURCHASEORDER": "4500000008"}, readonly=None), "read-only"),
    ("N18 bypass: wrong sidecar mode header", T, "POST", ex("BAPI_PO_GETDETAIL1"), body("BAPI_PO_GETDETAIL1", {"PURCHASEORDER": "4500000008"}), "business-operations"),
    ("N19 bypass: body names another function", T, "POST", ex("BAPI_PO_GETDETAIL1"), body("RFC_READ_TABLE", {"QUERY_TABLE": "USR02"}), "read-only"),
    ("N20 bypass: no token", T, "NOAUTH", "/v1/catalog/functions", "", ""),
    ("P21 maxRows above the approved limit is clamped", T, "POST", ex("BAPI_PO_GETDETAIL1"), body("BAPI_PO_GETDETAIL1", {"PURCHASEORDER": "4500000008"}, options={"maxRows": 51}), "read-only"),
    ("P22 dry run validates without calling SAP", T, "POST", ex("BAPI_VENDOR_GETDETAIL"), body("BAPI_VENDOR_GETDETAIL", {"VENDORNO": "10300001"}, options={"dryRun": True}), "read-only"),
    ("P23 admin search (metadata search approved)", T, "GET", "/v1/catalog/search?pattern=BAPI_PO_GET*&limit=10", "", ""),
    ("N24 search denied by SAP (T422_RFC)", R, "GET", "/v1/catalog/search?pattern=BAPI_PO_GET*&limit=10", "", ""),
    ("N25 execution denied by SAP S_RFC (T422_RFC)", R, "POST", ex("BAPI_PO_GETDETAIL1"), body("BAPI_PO_GETDETAIL1", {"PURCHASEORDER": "4500000008"}), "read-only"),
    ("P26 metadata still readable for T422_RFC", R, "GET", "/v1/catalog/functions/BAPI_VENDOR_GETDETAIL", "", ""),
    ("N27 duplicated key in parametersJson", T, "POST", ex("BAPI_PO_GETDETAIL1"), body("BAPI_PO_GETDETAIL1", raw='{"PURCHASEORDER":"4500000008","PURCHASEORDER":"4500000013"}'), "read-only"),
]
lines = ["C=/tmp/c415-catalog-probe/catcall.sh", "D=/tmp/c415-catalog-test"]
for name, host, method, path, payload, mode in cases:
    lines.append(f"echo {shlex.quote('### ' + name)}")
    if method == "NOAUTH":
        lines.append(f"sudo -n docker run --rm --network c415-catalog-test-net -v $D/tls/server.crt:/ca.crt:ro curlimages/curl:8.11.1 -sS --cacert /ca.crt -w '\\nHTTP %{{http_code}}\\n' https://{host}:8080{path}")
    elif method == "GET":
        lines.append(f"$C {host} GET {shlex.quote(path)}")
    else:
        lines.append(f"$C {host} POST {shlex.quote(path)} {shlex.quote(payload)} {mode}")
print("\n".join(lines))
