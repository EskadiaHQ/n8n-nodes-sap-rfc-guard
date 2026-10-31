package com.logali.rfcguard;

import java.util.Properties;

/** Read-only discovery probe: prints metadata and the outcome of discovery RFCs. No writes. */
public final class CatalogProbe {
  public static void main(String[] args) throws Exception {
    var configuration = Configuration.fromEnvironment();
    var properties = new Properties();
    properties.putAll(configuration.jcoProperties());
    JcoReflection.registerProvider(configuration.destinationName(), properties);
    Object destination = JcoReflection.invokeStatic(
        "com.sap.conn.jco.JCoDestinationManager", "getDestination", configuration.destinationName());
    Object attributes = JcoReflection.invoke(destination, "getAttributes");
    System.out.println("BACKEND sys=" + JcoReflection.invoke(attributes, "getSystemID")
        + " client=" + JcoReflection.invoke(attributes, "getClient")
        + " user=" + JcoReflection.invoke(attributes, "getUser")
        + " release=" + JcoReflection.invoke(attributes, "getRelease"));
    Object repository = JcoReflection.invoke(destination, "getRepository");
    for (String name : args) {
      if (name.startsWith("exec:")) { exec(destination, repository, name.substring(5)); continue; }
      System.out.println("=== META " + name);
      try {
        Object template = JcoReflection.invoke(repository, "getFunctionTemplate", name);
        if (template == null) { System.out.println("  NULL (not found)"); continue; }
        for (String list : new String[]{"getImportParameterList", "getExportParameterList",
            "getChangingParameterList", "getTableParameterList"}) {
          Object meta = JcoReflection.invoke(template, list);
          if (meta == null) continue;
          int count = (Integer) JcoReflection.invoke(meta, "getFieldCount");
          for (int i = 0; i < count; i++) {
            System.out.println("  " + list.replace("get", "").replace("ParameterList", "")
                + " " + JcoReflection.invoke(meta, "getName", i)
                + " type=" + JcoReflection.invoke(meta, "getTypeAsString", i)
                + " len=" + JcoReflection.invoke(meta, "getByteLength", i)
                + " dec=" + JcoReflection.invoke(meta, "getDecimals", i)
                + " opt=" + JcoReflection.invoke(meta, "isOptional", i)
                + " rec=" + JcoReflection.invoke(meta, "getRecordTypeName", i)
                + " default=" + JcoReflection.invoke(meta, "getDefault", i)
                + " desc=" + JcoReflection.invoke(meta, "getDescription", i));
          }
        }
        Object exceptions = JcoReflection.invoke(template, "getExceptionList");
        if (exceptions instanceof Object[] list) {
          for (Object e : list) System.out.println("  EXC " + JcoReflection.invoke(e, "getKey"));
        }
      } catch (RuntimeException error) {
        System.out.println("  ERROR " + error.getClass().getSimpleName() + ": " + error.getMessage());
      }
    }
  }

  /** exec:FUNC|IMPORT=VALUE,IMPORT=VALUE|TABLE (prints first rows of TABLE). */
  private static void exec(Object destination, Object repository, String spec) {
    String[] parts = spec.split("\\|");
    System.out.println("=== EXEC " + spec);
    try {
      Object function = JcoReflection.invoke(repository, "getFunction", parts[0]);
      Object imports = JcoReflection.invoke(function, "getImportParameterList");
      if (parts.length > 1 && !parts[1].isBlank()) {
        for (String pair : parts[1].split(",")) {
          String[] kv = pair.split("=", 2);
          JcoReflection.invoke(imports, "setValue", kv[0], kv[1]);
        }
      }
      long start = System.nanoTime();
      JcoReflection.invoke(function, "execute", destination);
      System.out.println("  OK ms=" + (System.nanoTime() - start) / 1_000_000);
      Object exports = JcoReflection.invoke(function, "getExportParameterList");
      if (exports != null) System.out.println("  EXPORTS " + JcoReflection.invoke(exports, "toXML"));
      if (parts.length > 2) {
        Object tables = JcoReflection.invoke(function, "getTableParameterList");
        Object table = JcoReflection.invoke(tables, "getTable", parts[2]);
        int rows = (Integer) JcoReflection.invoke(table, "getNumRows");
        System.out.println("  ROWS " + rows);
        String filter = parts.length > 3 ? parts[3] : null;
        int printed = 0;
        for (int i = 0; i < rows && printed < 12; i++) {
          JcoReflection.invoke(table, "setRow", i);
          String line = String.valueOf(JcoReflection.invoke(table, "toXML"));
          if (filter == null || line.contains(filter)) { System.out.println("  " + line); printed++; }
        }
      }
    } catch (RuntimeException error) {
      Throwable cause = error.getCause() == null ? error : error.getCause();
      String group = "";
      try { group = " group=" + JcoReflection.invoke(cause, "getGroup") + " key=" + JcoReflection.invoke(cause, "getKey"); }
      catch (RuntimeException ignored) { }
      System.out.println("  ERROR " + cause.getClass().getName() + group + ": " + cause.getMessage());
    }
  }
}
