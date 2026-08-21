package com.logali.rfcguard;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

final class JcoSapAdapter implements SapAdapter {
  private static final String USER_LIST_BAPI = "BAPI_USER_GETLIST";
  private static final String USER_DETAIL_BAPI = "BAPI_USER_GET_DETAIL";
  private final Configuration configuration;
  private final Object destination;
  private final Clock clock;

  JcoSapAdapter(Configuration configuration) {
    this(configuration, Clock.systemUTC());
  }

  JcoSapAdapter(Configuration configuration, Clock clock) {
    this.configuration = configuration;
    this.clock = clock;
    Properties properties = new Properties();
    properties.putAll(configuration.jcoProperties());
    JcoReflection.registerProvider(configuration.destinationName(), properties);
    this.destination = JcoReflection.invokeStatic(
        "com.sap.conn.jco.JCoDestinationManager", "getDestination", configuration.destinationName());
  }

  @Override public void ping() { JcoReflection.invoke(destination, "ping"); }

  @Override public Backend backend() {
    Object attributes = JcoReflection.invoke(destination, "getAttributes");
    return new Backend(attribute(attributes, "getSystemID"), attribute(attributes, "getClient"),
        attribute(attributes, "getPartnerHost"), attribute(attributes, "getRelease"));
  }

  @Override public List<UserRecord> listUsers(Map<String, Object> parameters) {
    int requested = integer(parameters.get("maxRows"), configuration.maxRows());
    int limit = Math.min(Math.max(1, requested), configuration.maxRows());
    Object function = function(USER_LIST_BAPI);
    Object imports = JcoReflection.invoke(function, "getImportParameterList");
    JcoReflection.invoke(imports, "setValue", "MAX_ROWS", Integer.toString(limit));
    JcoReflection.invoke(imports, "setValue", "WITH_USERNAME", "X");
    execute(function);
    assertNoBapiErrors(function);

    Object tables = JcoReflection.invoke(function, "getTableParameterList");
    Object userList = JcoReflection.invoke(tables, "getTable", "USERLIST");
    int count = (Integer) JcoReflection.invoke(userList, "getNumRows");
    var output = new ArrayList<UserRecord>(Math.min(count, limit));
    for (int index = 0; index < count && output.size() < limit; index++) {
      JcoReflection.invoke(userList, "setRow", index);
      String username = JcoReflection.string(userList, "USERNAME");
      if (!username.isBlank()) output.add(readDetail(username));
    }
    return applyFilters(output, parameters);
  }

  @Override public List<UserRecord> getUser(String username) {
    if (username == null || !username.matches("[A-Za-z0-9_.@\\-]{1,40}")) {
      throw new IllegalArgumentException("USERNAME_INVALID");
    }
    return List.of(readDetail(username.toUpperCase()));
  }

  private UserRecord readDetail(String username) {
    Object function = function(USER_DETAIL_BAPI);
    Object imports = JcoReflection.invoke(function, "getImportParameterList");
    JcoReflection.invoke(imports, "setValue", "USERNAME", username);
    JcoReflection.invoke(imports, "setValue", "CACHE_RESULTS", "X");
    execute(function);
    assertNoBapiErrors(function);

    Object exports = JcoReflection.invoke(function, "getExportParameterList");
    Object logon = JcoReflection.invoke(exports, "getStructure", "LOGONDATA");
    Object address = JcoReflection.invoke(exports, "getStructure", "ADDRESS");
    Object admin = JcoReflection.invoke(exports, "getStructure", "ADMINDATA");
    Object locked = JcoReflection.invoke(exports, "getStructure", "ISLOCKED");
    var raw = new LinkedHashMap<String, String>();
    raw.put("username", username);
    raw.put("userType", JcoReflection.string(logon, "USTYP"));
    raw.put("validFrom", JcoReflection.string(logon, "GLTGV"));
    raw.put("validTo", JcoReflection.string(logon, "GLTGB"));
    raw.put("fullName", firstNonBlank(JcoReflection.string(address, "FULLNAME"),
        (JcoReflection.string(address, "FIRSTNAME") + " " + JcoReflection.string(address, "LASTNAME")).trim()));
    raw.put("email", JcoReflection.string(address, "E_MAIL"));
    raw.put("createdAt", JcoReflection.string(admin, "ERDAT"));
    raw.put("lastLogonAt", JcoReflection.string(admin, "TRDAT"));
    raw.put("localLock", JcoReflection.string(locked, "LOCAL_LOCK"));
    raw.put("globalLock", JcoReflection.string(locked, "GLOB_LOCK"));
    raw.put("wrongLogonLock", JcoReflection.string(locked, "WRNG_LOGON"));
    raw.put("noPassword", JcoReflection.string(locked, "NO_USER_PW"));
    return UserRecord.classify(raw, configuration.inactiveDays(), LocalDate.now(clock));
  }

  private List<UserRecord> applyFilters(List<UserRecord> input, Map<String, Object> parameters) {
    String status = string(parameters.get("accountStatus"));
    String type = string(parameters.get("userType"));
    return input.stream()
        .filter(user -> status.isBlank() || user.accountStatus().equalsIgnoreCase(status))
        .filter(user -> type.isBlank() || user.userType().equalsIgnoreCase(type))
        .toList();
  }

  private Object function(String name) {
    Object repository = JcoReflection.invoke(destination, "getRepository");
    Object function = JcoReflection.invoke(repository, "getFunction", name);
    if (function == null) throw new IllegalStateException("Required SAP function is unavailable");
    return function;
  }

  private void execute(Object function) { JcoReflection.invoke(function, "execute", destination); }

  private void assertNoBapiErrors(Object function) {
    Object tables = JcoReflection.invoke(function, "getTableParameterList");
    Object returns = JcoReflection.invoke(tables, "getTable", "RETURN");
    int count = (Integer) JcoReflection.invoke(returns, "getNumRows");
    for (int index = 0; index < count; index++) {
      JcoReflection.invoke(returns, "setRow", index);
      String type = JcoReflection.string(returns, "TYPE");
      if ("E".equals(type) || "A".equals(type) || "X".equals(type)) {
        throw new IllegalStateException("SAP rejected the governed read: " + JcoReflection.string(returns, "MESSAGE"));
      }
    }
  }

  private static String attribute(Object attributes, String getter) {
    try { return String.valueOf(JcoReflection.invoke(attributes, getter)); }
    catch (RuntimeException ignored) { return ""; }
  }

  private static int integer(Object input, int fallback) {
    try { return input == null ? fallback : Integer.parseInt(input.toString()); }
    catch (NumberFormatException ignored) { return fallback; }
  }

  private static String string(Object input) { return input == null ? "" : input.toString().trim(); }
  private static String firstNonBlank(String first, String second) { return first == null || first.isBlank() ? second : first; }
}
