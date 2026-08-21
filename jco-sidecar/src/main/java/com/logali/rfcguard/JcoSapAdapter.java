package com.logali.rfcguard;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

final class JcoSapAdapter implements SapAdapter {
  private static final String USER_LIST_BAPI = "BAPI_USER_GETLIST";
  private static final String USER_DETAIL_BAPI = "BAPI_USER_GET_DETAIL";
  private static final String USER_CREATE_BAPI = "BAPI_USER_CREATE1";
  private static final String COMMIT_BAPI = "BAPI_TRANSACTION_COMMIT";
  private static final String ROLLBACK_BAPI = "BAPI_TRANSACTION_ROLLBACK";
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
    assertConfiguredClient(parameters);
    int limit = boundedInteger(parameters.get("maxRows"), configuration.maxRows(),
        1, configuration.maxRows(), "MAX_ROWS_INVALID");
    int inactiveDays = boundedInteger(parameters.get("inactiveDays"), configuration.inactiveDays(),
        1, 3650, "INACTIVE_DAYS_INVALID");
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
      if (!username.isBlank()) output.add(readDetail(username, inactiveDays));
    }
    return applyFilters(output, parameters);
  }

  @Override public List<UserRecord> getUser(String username) {
    if (username == null || !username.matches("[A-Za-z0-9_.@\\-]{1,40}")) {
      throw new IllegalArgumentException("USERNAME_INVALID");
    }
    return List.of(readDetail(username.toUpperCase(), configuration.inactiveDays()));
  }

  @Override public Map<String, Object> createCommunicationUser(Map<String, Object> parameters) {
    assertConfiguredClient(parameters);
    if (!configuration.userCreationEnabled() || !"user-provisioning".equals(configuration.mode())) {
      throw new IllegalArgumentException("USER_CREATION_DISABLED");
    }
    String username = string(parameters.get("username")).toUpperCase();
    if (!username.matches("[A-Z0-9_]{1,12}")
        || !username.startsWith(configuration.userCreatePrefix())) {
      throw new IllegalArgumentException("USERNAME_PREFIX_INVALID");
    }
    String firstName = boundedText(parameters.get("firstName"), 1, 40, "FIRST_NAME_INVALID");
    String lastName = boundedText(parameters.get("lastName"), 1, 40, "LAST_NAME_INVALID");
    String email = string(parameters.get("email"));
    if (!email.isBlank() && !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
      throw new IllegalArgumentException("EMAIL_INVALID");
    }
    int validDays = boundedInteger(parameters.get("validDays"), 1, 1,
        configuration.userCreateMaxValidityDays(), "VALID_DAYS_INVALID");
    LocalDate validFrom = LocalDate.now(clock);
    LocalDate validTo = validFrom.plusDays(validDays - 1L);

    Object function = function(USER_CREATE_BAPI);
    Object imports = JcoReflection.invoke(function, "getImportParameterList");
    JcoReflection.invoke(imports, "setValue", "USERNAME", username);
    Object logon = JcoReflection.invoke(imports, "getStructure", "LOGONDATA");
    JcoReflection.invoke(logon, "setValue", "USTYP", "C");
    JcoReflection.invoke(logon, "setValue", "CLASS", configuration.userCreateGroup());
    JcoReflection.invoke(logon, "setValue", "GLTGV", validFrom.format(DateTimeFormatter.BASIC_ISO_DATE));
    JcoReflection.invoke(logon, "setValue", "GLTGB", validTo.format(DateTimeFormatter.BASIC_ISO_DATE));
    Object password = JcoReflection.invoke(imports, "getStructure", "PASSWORD");
    JcoReflection.invoke(password, "setValue", "BAPIPWD", configuration.newUserInitialPassword());
    Object address = JcoReflection.invoke(imports, "getStructure", "ADDRESS");
    JcoReflection.invoke(address, "setValue", "FIRSTNAME", firstName);
    JcoReflection.invoke(address, "setValue", "LASTNAME", lastName);
    if (!email.isBlank()) JcoReflection.invoke(address, "setValue", "E_MAIL", email);
    JcoReflection.invoke(address, "setValue", "LANGU", "E");
    try {
      execute(function);
      assertNoBapiErrors(function, "SAP rejected the governed user creation");
      commit();
    } catch (RuntimeException error) {
      rollback();
      throw error;
    }

    UserRecord verified = readDetail(username, configuration.inactiveDays());
    var result = new LinkedHashMap<String, Object>();
    result.put("username", username);
    result.put("created", true);
    result.put("verified", username.equals(verified.username()));
    result.put("userType", "Communication");
    result.put("userGroup", configuration.userCreateGroup());
    result.put("validFrom", validFrom.toString());
    result.put("validTo", validTo.toString());
    result.put("accountStatus", verified.accountStatus());
    result.put("rolesAssigned", 0);
    result.put("profilesAssigned", 0);
    result.put("passwordReturned", false);
    return result;
  }

  private UserRecord readDetail(String username, int inactiveDays) {
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
    raw.put("lastLogonAt", timestamp(
        JcoReflection.string(admin, "TRDAT"), optionalString(logon, "LTIME")));
    raw.put("localLock", JcoReflection.string(locked, "LOCAL_LOCK"));
    raw.put("globalLock", JcoReflection.string(locked, "GLOB_LOCK"));
    raw.put("wrongLogonLock", JcoReflection.string(locked, "WRNG_LOGON"));
    raw.put("noPassword", JcoReflection.string(locked, "NO_USER_PW"));
    return UserRecord.classify(raw, inactiveDays, LocalDate.now(clock));
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
    assertNoBapiErrors(function, "SAP rejected the governed read");
  }

  private void assertNoBapiErrors(Object function, String prefix) {
    Object tables = JcoReflection.invoke(function, "getTableParameterList");
    Object returns = JcoReflection.invoke(tables, "getTable", "RETURN");
    int count = (Integer) JcoReflection.invoke(returns, "getNumRows");
    for (int index = 0; index < count; index++) {
      JcoReflection.invoke(returns, "setRow", index);
      String type = JcoReflection.string(returns, "TYPE");
      if ("E".equals(type) || "A".equals(type) || "X".equals(type)) {
        throw new IllegalStateException(prefix + ": " + JcoReflection.string(returns, "MESSAGE"));
      }
    }
  }

  private void commit() {
    Object function = function(COMMIT_BAPI);
    Object imports = JcoReflection.invoke(function, "getImportParameterList");
    JcoReflection.invoke(imports, "setValue", "WAIT", "X");
    execute(function);
  }

  private void rollback() {
    try { execute(function(ROLLBACK_BAPI)); }
    catch (RuntimeException ignored) { /* Preserve the original SAP error. */ }
  }

  private static String attribute(Object attributes, String getter) {
    try { return String.valueOf(JcoReflection.invoke(attributes, getter)); }
    catch (RuntimeException ignored) { return ""; }
  }

  private void assertConfiguredClient(Map<String, Object> parameters) {
    Object requested = parameters.get("client");
    if (requested == null) return;
    String configured = configuration.jcoProperties().getOrDefault("jco.client.client", "");
    if (!requested.toString().equals(configured)) throw new IllegalArgumentException("CLIENT_MISMATCH");
  }

  private static int boundedInteger(
      Object input, int fallback, int minimum, int maximum, String errorCode) {
    if (input == null) return fallback;
    try {
      int value = Integer.parseInt(input.toString());
      if (value < minimum || value > maximum) throw new NumberFormatException();
      return value;
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(errorCode);
    }
  }

  private static String string(Object input) { return input == null ? "" : input.toString().trim(); }
  private static String boundedText(Object input, int minimum, int maximum, String errorCode) {
    String value = string(input);
    if (value.length() < minimum || value.length() > maximum) {
      throw new IllegalArgumentException(errorCode);
    }
    return value;
  }
  private static String firstNonBlank(String first, String second) { return first == null || first.isBlank() ? second : first; }

  private static String optionalString(Object structure, String field) {
    try { return JcoReflection.string(structure, field); }
    catch (RuntimeException ignored) { return ""; }
  }

  static String timestamp(String rawDate, String rawTime) {
    String date = digits(rawDate, 8);
    if (date.isBlank() || "00000000".equals(date)) return "";
    String formattedDate = date.substring(0, 4) + "-" + date.substring(4, 6) + "-" + date.substring(6, 8);
    String time = digits(rawTime, 6);
    if (time.isBlank() || "000000".equals(time)) return formattedDate;
    return formattedDate + "T" + time.substring(0, 2) + ":" + time.substring(2, 4) + ":" + time.substring(4, 6);
  }

  private static String digits(String input, int maximum) {
    if (input == null) return "";
    String digits = input.replaceAll("[^0-9]", "");
    return digits.length() >= maximum ? digits.substring(0, maximum) : "";
  }
}
