# SAP compatibility

The n8n node is independent of the SAP database. It can therefore be used with
SAP R/3, ECC or S/4HANA when the target ABAP system, the selected SAP JCo build
and the approved RFC interfaces are mutually compatible.

## Required checks

Before enabling a real credential, Basis must verify in the target system:

1. `BAPI_USER_GETLIST` exists, is remote-enabled and exposes `MAX_ROWS`,
   `WITH_USERNAME`, `USERLIST` and `RETURN`.
2. `BAPI_USER_GET_DETAIL` exists, is remote-enabled and exposes the structures
   used by the adapter: `LOGONDATA`, `ADDRESS`, `ADMINDATA`, `ISLOCKED` and
   `RETURN`.
3. `ADMINDATA-TRDAT` supplies the last-logon date. `LOGONDATA-LTIME` is used
   when present; older systems may return only the date.
4. The installed JCo package supports the server platform and Java runtime.
5. The technical identity passes a positive trace for the two reads and a
   negative trace for an unrelated RFC and every user-maintenance BAPI.
6. For business-data resources, verify the exact signatures and RFC flag of
   `BAPI_COMPANYCODE_GETLIST`, `BAPI_COMPANYCODE_GETDETAIL`,
   `BAPI_MATERIAL_GETLIST`, `BAPI_MATERIAL_GET_DETAIL`,
   `BAPI_PO_GETDETAIL1` and `BAPI_SALESORDER_GETSTATUS`; enable only the aliases
   actually authorized for that destination.

SAP documents `BAPI_USER_GETLIST` as belonging to the R/3 user business object
from release 6.20. Do not claim universal support for older R/3 releases. If the
list BAPI is absent, the supported choices are a narrowly scoped custom read RFC
or the governed snapshot pattern; unrestricted `RFC_READ_TABLE` is not a
replacement.

## Destination modes

| Mode | Required settings | Typical use |
|---|---|---|
| Direct application server | `SAP_ASHOST`, `SAP_SYSNR`, `SAP_CLIENT` | Development or a fixed application server |
| Message server | `SAP_MSHOST`, `SAP_R3NAME`, `SAP_GROUP`, `SAP_CLIENT` | Load-balanced ECC/S/4HANA landscapes |

The SAP client is fixed in the operated destination. A workflow may state the
expected three-digit client for evidence, but the sidecar rejects a mismatch;
the workflow cannot switch mandants.

## Time semantics

`lastLogonAt` is emitted as `YYYY-MM-DD` or `YYYY-MM-DDTHH:mm:ss`. No `Z` suffix
is added because these standard structures do not provide a UTC offset. The
value represents the SAP system's logon date/time and must not be presented as
UTC unless the system time-zone configuration proves that interpretation.
