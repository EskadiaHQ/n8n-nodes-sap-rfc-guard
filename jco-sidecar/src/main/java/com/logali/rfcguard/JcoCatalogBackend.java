package com.logali.rfcguard;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Catalog access through the sidecar's own JCo destination; the SAP credential never leaves it. */
final class JcoCatalogBackend implements CatalogBackend {
  private static final int MAX_DEPTH = 4;
  private static final int MAX_FIELDS = 600;
  private static final String JCO_CONTEXT = "com.sap.conn.jco.JCoContext";
  private final JcoSapAdapter adapter;
  private final Object destination;
  private final String language;

  JcoCatalogBackend(JcoSapAdapter adapter, String language) {
    this.adapter = adapter;
    this.destination = adapter.jcoDestination();
    this.language = language == null || language.isBlank() ? "E" : language.trim().substring(0, 1);
  }

  @Override public SapAdapter.Backend backend() { return adapter.backend(); }

  @Override public Signature signature(String function) {
    try {
      Object template = JcoReflection.invoke(repository(), "getFunctionTemplate", function);
      if (template == null) return null;
      var parameters = new ArrayList<Parameter>();
      describeList(parameters, JcoReflection.invoke(template, "getImportParameterList"), "IMPORT");
      describeList(parameters, JcoReflection.invoke(template, "getExportParameterList"), "EXPORT");
      describeList(parameters, JcoReflection.invoke(template, "getChangingParameterList"), "CHANGING");
      describeList(parameters, JcoReflection.invoke(template, "getTableParameterList"), "TABLES");
      var exceptions = new ArrayList<String>();
      if (JcoReflection.invoke(template, "getExceptionList") instanceof Object[] list) {
        for (Object item : list) exceptions.add(String.valueOf(JcoReflection.invoke(item, "getKey")));
      }
      return new Signature(function, List.copyOf(parameters), List.copyOf(exceptions));
    } catch (RuntimeException error) {
      throw classify(error);
    }
  }

  @Override public RemoteInfo remoteInfo(String function) {
    try {
      Object call = function("RFC_GET_FUNCTION_INTERFACE");
      Object imports = JcoReflection.invoke(call, "getImportParameterList");
      JcoReflection.invoke(imports, "setValue", "FUNCNAME", function);
      JcoReflection.invoke(imports, "setValue", "LANGUAGE", language);
      execute(call);
      Object exports = JcoReflection.invoke(call, "getExportParameterList");
      return new RemoteInfo("R".equals(JcoReflection.string(exports, "REMOTE_CALL")),
          !JcoReflection.string(exports, "UPDATE_TASK").isBlank());
    } catch (RuntimeException error) {
      var classified = classify(error);
      if ("FU_NOT_FOUND".equals(classified.sapKey())) return null;
      throw classified;
    }
  }

  @Override public List<SearchHit> search(String pattern, int maxResults) {
    try {
      Object call = function("RFC_FUNCTION_SEARCH");
      Object imports = JcoReflection.invoke(call, "getImportParameterList");
      JcoReflection.invoke(imports, "setValue", "FUNCNAME", pattern);
      JcoReflection.invoke(imports, "setValue", "LANGUAGE", language);
      execute(call);
      Object table = table(call, "FUNCTIONS");
      int rows = (Integer) JcoReflection.invoke(table, "getNumRows");
      var hits = new ArrayList<SearchHit>();
      for (int index = 0; index < rows && hits.size() < maxResults; index++) {
        JcoReflection.invoke(table, "setRow", index);
        hits.add(new SearchHit(JcoReflection.string(table, "FUNCNAME"),
            JcoReflection.string(table, "GROUPNAME"), JcoReflection.string(table, "STEXT")));
      }
      return hits;
    } catch (RuntimeException error) {
      var classified = classify(error);
      if ("NO_FUNCTION_FOUND".equals(classified.sapKey())) return List.of();
      throw classified;
    }
  }

  @Override public Map<String, List<BorMethod>> borMethods() {
    try {
      var released = new java.util.HashSet<String>();
      var result = new LinkedHashMap<String, List<BorMethod>>();
      for (boolean implemented : new boolean[]{false, true}) {
        Object call = function("SWO_QUERY_API_METHODS");
        Object imports = JcoReflection.invoke(call, "getImportParameterList");
        JcoReflection.invoke(imports, "setValue", "LANGUAGE", language);
        JcoReflection.invoke(imports, "setValue", "WITH_OBJECT_NAMES", "X");
        if (implemented) JcoReflection.invoke(imports, "setValue", "WITH_IMPL_METHODS", "X");
        execute(call);
        Object table = table(call, "API_METHODS");
        int rows = (Integer) JcoReflection.invoke(table, "getNumRows");
        for (int index = 0; index < rows; index++) {
          JcoReflection.invoke(table, "setRow", index);
          String function = JcoReflection.string(table, "FUNCTION");
          String objectType = JcoReflection.string(table, "OBJTYPE");
          String method = JcoReflection.string(table, "METHOD");
          String key = objectType + "." + method;
          if (!implemented) released.add(key);
          else if (!function.isBlank()) {
            result.computeIfAbsent(function, ignored -> new ArrayList<>()).add(new BorMethod(objectType,
                method, JcoReflection.string(table, "OBJECTNAME"),
                JcoReflection.string(table, "SHORTTEXT"), released.contains(key)));
          }
        }
      }
      result.replaceAll((ignored, methods) -> List.copyOf(methods));
      return Map.copyOf(result);
    } catch (RuntimeException error) {
      throw classify(error);
    }
  }

  @Override public List<String> bapiDocumentation(String objectType, String method, int maxLines) {
    try {
      Object call = function("BAPI_INTERFACE_GETDOCU");
      Object imports = JcoReflection.invoke(call, "getImportParameterList");
      JcoReflection.invoke(imports, "setValue", "OBJTYPE", objectType);
      JcoReflection.invoke(imports, "setValue", "METHOD", method);
      JcoReflection.invoke(imports, "setValue", "TEXTFORMAT", "ASC");
      JcoReflection.invoke(imports, "setValue", "LANGUAGE", language);
      execute(call);
      Object table = table(call, "TEXT");
      int rows = (Integer) JcoReflection.invoke(table, "getNumRows");
      var lines = new ArrayList<String>();
      for (int index = 0; index < rows && lines.size() < maxLines; index++) {
        JcoReflection.invoke(table, "setRow", index);
        lines.add(JcoReflection.string(table, "LINE"));
      }
      return lines;
    } catch (RuntimeException error) {
      throw classify(error);
    }
  }

  @Override public CallResult call(CallRequest request) {
    try {
      Object call = function(request.function());
      write(JcoReflection.invoke(call, "getImportParameterList"), request.imports());
      write(JcoReflection.invoke(call, "getChangingParameterList"), request.changing());
      write(JcoReflection.invoke(call, "getTableParameterList"), request.tables());
      String transaction = "none";
      if (request.rollbackAfterCall()) {
        JcoReflection.invokeStatic(JCO_CONTEXT, "begin", destination);
        try {
          execute(call);
          execute(function("BAPI_TRANSACTION_ROLLBACK"));
          transaction = "rolled-back";
        } finally {
          try { JcoReflection.invokeStatic(JCO_CONTEXT, "end", destination); }
          catch (RuntimeException ignored) { /* JCo returns the connection to the pool. */ }
        }
      } else {
        execute(call);
      }
      var tableRows = new LinkedHashMap<String, Integer>();
      return new CallResult(
          read(JcoReflection.invoke(call, "getExportParameterList"), request, tableRows),
          read(JcoReflection.invoke(call, "getChangingParameterList"), request, tableRows),
          read(JcoReflection.invoke(call, "getTableParameterList"), request, tableRows),
          tableRows, transaction);
    } catch (RuntimeException error) {
      throw classify(error);
    }
  }

  private Object repository() { return JcoReflection.invoke(destination, "getRepository"); }

  private Object function(String name) {
    Object function = JcoReflection.invoke(repository(), "getFunction", name);
    if (function == null) throw new SapCallException("SAP", "SAP_FUNCTION_NOT_FOUND", "FU_NOT_FOUND", name);
    return function;
  }

  private void execute(Object function) { JcoReflection.invoke(function, "execute", destination); }

  private static Object table(Object function, String name) {
    return JcoReflection.invoke(JcoReflection.invoke(function, "getTableParameterList"), "getTable", name);
  }

  private static void describeList(List<Parameter> target, Object meta, String direction) {
    if (meta == null) return;
    int count = (Integer) JcoReflection.invoke(meta, "getFieldCount");
    for (int index = 0; index < count; index++) {
      String type = String.valueOf(JcoReflection.invoke(meta, "getTypeAsString", index));
      List<Field> fields = complex(type)
          ? describeRecord(JcoReflection.invoke(meta, "getRecordMetaData", index), 1) : List.of();
      target.add(new Parameter(String.valueOf(JcoReflection.invoke(meta, "getName", index)),
          direction, type, (Integer) JcoReflection.invoke(meta, "getLength", index),
          (Integer) JcoReflection.invoke(meta, "getDecimals", index),
          (Boolean) JcoReflection.invoke(meta, "isOptional", index),
          text(JcoReflection.invoke(meta, "getDefault", index)),
          text(JcoReflection.invoke(meta, "getDescription", index)),
          text(JcoReflection.invoke(meta, "getRecordTypeName", index)), fields));
    }
  }

  private static List<Field> describeRecord(Object meta, int depth) {
    if (meta == null || depth > MAX_DEPTH) return List.of();
    int count = Math.min(MAX_FIELDS, (Integer) JcoReflection.invoke(meta, "getFieldCount"));
    var fields = new ArrayList<Field>(count);
    for (int index = 0; index < count; index++) {
      String type = String.valueOf(JcoReflection.invoke(meta, "getTypeAsString", index));
      String recordType = "";
      List<Field> nested = List.of();
      if (complex(type)) {
        Object nestedMeta = JcoReflection.invoke(meta, "getRecordMetaData", index);
        recordType = nestedMeta == null ? "" : text(JcoReflection.invoke(nestedMeta, "getName"));
        nested = describeRecord(nestedMeta, depth + 1);
      }
      fields.add(new Field(String.valueOf(JcoReflection.invoke(meta, "getName", index)), type,
          (Integer) JcoReflection.invoke(meta, "getLength", index),
          (Integer) JcoReflection.invoke(meta, "getDecimals", index),
          text(JcoReflection.invoke(meta, "getDescription", index)), recordType, nested));
    }
    return List.copyOf(fields);
  }

  @SuppressWarnings("unchecked")
  private static void write(Object record, Map<String, Object> values) {
    if (values.isEmpty()) return;
    if (record == null) throw new IllegalStateException("The SAP parameter list is unavailable");
    for (var entry : values.entrySet()) {
      Object value = entry.getValue();
      if (value instanceof Map<?, ?> structure) {
        writeRecord(JcoReflection.invoke(record, "getStructure", entry.getKey()),
            (Map<String, Object>) structure);
      } else if (value instanceof List<?> rows) {
        Object table = JcoReflection.invoke(record, "getTable", entry.getKey());
        for (Object row : rows) {
          JcoReflection.invoke(table, "appendRow");
          writeRecord(table, (Map<String, Object>) row);
        }
      } else {
        JcoReflection.invoke(record, "setValue", entry.getKey(), value);
      }
    }
  }

  private static void writeRecord(Object record, Map<String, Object> values) { write(record, values); }

  private static Map<String, Object> read(Object list, CallRequest request, Map<String, Integer> tableRows) {
    var output = new LinkedHashMap<String, Object>();
    if (list == null) return output;
    Object meta = JcoReflection.invoke(list, "getMetaData");
    int count = (Integer) JcoReflection.invoke(meta, "getFieldCount");
    for (int index = 0; index < count; index++) {
      String name = String.valueOf(JcoReflection.invoke(meta, "getName", index));
      if (!request.outputs().contains(name)) continue;
      String type = String.valueOf(JcoReflection.invoke(meta, "getTypeAsString", index));
      if ("TABLE".equals(type)) {
        Object table = JcoReflection.invoke(list, "getTable", index);
        tableRows.put(name, (Integer) JcoReflection.invoke(table, "getNumRows"));
      }
      output.put(name, value(list, meta, index, type, request.maxRows(), 1));
    }
    return output;
  }

  private static Object value(Object record, Object meta, int index, String type, int maxRows, int depth) {
    return switch (type) {
      case "STRUCTURE" -> depth > MAX_DEPTH ? Map.of()
          : recordMap(JcoReflection.invoke(record, "getStructure", index), maxRows, depth + 1);
      case "TABLE" -> depth > MAX_DEPTH ? List.of()
          : tableRows(JcoReflection.invoke(record, "getTable", index), maxRows, depth + 1);
      case "BCD", "DECF16", "DECF34" -> {
        Object decimal = JcoReflection.invoke(record, "getBigDecimal", index);
        yield decimal == null ? "" : ((BigDecimal) decimal).toPlainString();
      }
      case "INT", "INT1", "INT2", "INT8" -> JcoReflection.invoke(record, "getValue", index);
      case "FLOAT" -> JcoReflection.invoke(record, "getDouble", index);
      case "DATE" -> JcoSapAdapter.normalizeDate(String.valueOf(JcoReflection.invoke(record, "getString", index)));
      case "BYTE", "XSTRING" -> {
        Object bytes = JcoReflection.invoke(record, "getByteArray", index);
        yield bytes == null ? "" : Base64.getEncoder().encodeToString((byte[]) bytes);
      }
      default -> {
        Object text = JcoReflection.invoke(record, "getString", index);
        yield text == null ? "" : text.toString();
      }
    };
  }

  private static Map<String, Object> recordMap(Object record, int maxRows, int depth) {
    var output = new LinkedHashMap<String, Object>();
    Object meta = JcoReflection.invoke(record, "getMetaData");
    int count = Math.min(MAX_FIELDS, (Integer) JcoReflection.invoke(meta, "getFieldCount"));
    for (int index = 0; index < count; index++) {
      String type = String.valueOf(JcoReflection.invoke(meta, "getTypeAsString", index));
      output.put(String.valueOf(JcoReflection.invoke(meta, "getName", index)),
          value(record, meta, index, type, maxRows, depth));
    }
    return output;
  }

  private static List<Map<String, Object>> tableRows(Object table, int maxRows, int depth) {
    int count = (Integer) JcoReflection.invoke(table, "getNumRows");
    var rows = new ArrayList<Map<String, Object>>(Math.min(count, maxRows));
    for (int index = 0; index < count && rows.size() < maxRows; index++) {
      JcoReflection.invoke(table, "setRow", index);
      rows.add(recordMap(table, maxRows, depth));
    }
    return rows;
  }

  private static boolean complex(String type) { return "STRUCTURE".equals(type) || "TABLE".equals(type); }

  private static String text(Object value) { return value == null ? "" : value.toString().trim(); }

  /** Maps JCo failures to transport, SAP authorization, ABAP exception or ABAP runtime categories. */
  static SapCallException classify(Throwable error) {
    if (error instanceof SapCallException classified) return classified;
    Throwable cause = error;
    while (cause != null && !cause.getClass().getName().startsWith("com.sap.conn.jco.")) {
      cause = cause.getCause();
    }
    if (cause == null) {
      return new SapCallException("TRANSPORT", "SAP_CALL_FAILED", "", safe(error.getMessage()));
    }
    String key = "";
    try { key = String.valueOf(JcoReflection.invoke(cause, "getKey")); }
    catch (RuntimeException ignored) { /* Older JCo builds expose only the message. */ }
    String message = safe(cause.getMessage());
    if (cause.getClass().getName().endsWith("AbapException")) {
      return new SapCallException("ABAP_EXCEPTION", "ABAP_EXCEPTION", key, message);
    }
    if (key.contains("LOGON")) return new SapCallException("TRANSPORT", "SAP_LOGON_FAILED", key, message);
    if (key.contains("SYSTEM_FAILURE") && message.contains("No RFC authorization")) {
      return new SapCallException("SAP_AUTHORIZATION", "SAP_RFC_NOT_AUTHORIZED", key, message);
    }
    if (key.contains("SYSTEM_FAILURE") || key.contains("ABAP_RUNTIME")) {
      return new SapCallException("ABAP_RUNTIME", "SAP_ABAP_RUNTIME_ERROR", key, message);
    }
    if (key.contains("FUNCTION_NOT_FOUND")) {
      return new SapCallException("SAP", "SAP_FUNCTION_NOT_FOUND", key, message);
    }
    if (key.contains("CONVERSION") || key.contains("FIELD_NOT_FOUND") || key.contains("ILLEGAL_ARGUMENT")) {
      return new SapCallException("REQUEST", "SAP_VALUE_REJECTED", key, message);
    }
    return new SapCallException("TRANSPORT", "SAP_TRANSPORT_FAILURE", key, message);
  }

  static String safe(String message) {
    if (message == null) return "";
    String value = message.replaceAll("[\\r\\n\\t]+", " ")
        .replaceAll("(?i)(password|passwd|token|authorization|cookie)\\s*[:=]\\s*\\S+", "$1=[REDACTED]");
    return value.substring(0, Math.min(value.length(), 300));
  }
}
