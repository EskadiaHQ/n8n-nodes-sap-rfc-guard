package com.logali.rfcguard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class GuardService {
  static final Set<String> OPERATIONS = Set.of(
      "listSu01Users", "getSu01UserDetail", "listSu01RiskAccounts", "summarizeSu01Accounts");
  private static final Map<String, Set<String>> PARAMETERS = Map.of(
      "listSu01Users", Set.of("client", "maxRows", "inactiveDays", "userType", "accountStatus"),
      "getSu01UserDetail", Set.of("client", "username"),
      "listSu01RiskAccounts", Set.of("client", "maxRows", "inactiveDays"),
      "summarizeSu01Accounts", Set.of("client", "maxRows", "inactiveDays", "dimension"));
  private static final Set<String> USER_TYPES = Set.of(
      "Dialog", "System", "Communication", "Reference", "Service");
  private static final Set<String> ACCOUNT_STATUSES = Set.of(
      "Active", "Locked", "Expired", "NotYetValid", "Inactive");
  private final SapAdapter adapter;

  GuardService(SapAdapter adapter) { this.adapter = adapter; }

  List<Map<String, Object>> execute(String operation, Map<String, Object> parameters) {
    validateParameters(operation, parameters);
    return switch (operation) {
      case "listSu01Users" -> maps(adapter.listUsers(parameters), false);
      case "getSu01UserDetail" -> maps(adapter.getUser(requiredUsername(parameters)), false);
      case "listSu01RiskAccounts" -> maps(adapter.listUsers(parameters).stream()
          .filter(user -> !"Active".equals(user.accountStatus())).toList(), true);
      case "summarizeSu01Accounts" -> summarize(adapter.listUsers(parameters), parameters);
      default -> throw new IllegalArgumentException("OPERATION_NOT_ALLOWED");
    };
  }

  private static String requiredUsername(Map<String, Object> parameters) {
    Object username = parameters.get("username");
    if (username == null || username.toString().isBlank()) throw new IllegalArgumentException("USERNAME_REQUIRED");
    return username.toString().trim();
  }

  private static void validateParameters(String operation, Map<String, Object> parameters) {
    Set<String> allowed = PARAMETERS.get(operation);
    if (allowed == null) throw new IllegalArgumentException("OPERATION_NOT_ALLOWED");
    if (!allowed.containsAll(parameters.keySet())) {
      throw new IllegalArgumentException("PARAMETER_NOT_ALLOWED");
    }
    optionalInteger(parameters, "maxRows", 1, 500, "MAX_ROWS_INVALID");
    optionalInteger(parameters, "inactiveDays", 1, 3650, "INACTIVE_DAYS_INVALID");

    Object client = parameters.get("client");
    if (client != null && !client.toString().matches("[0-9]{3}")) {
      throw new IllegalArgumentException("CLIENT_INVALID");
    }
    optionalEnum(parameters, "userType", USER_TYPES, "USER_TYPE_INVALID");
    optionalEnum(parameters, "accountStatus", ACCOUNT_STATUSES, "ACCOUNT_STATUS_INVALID");

    Object dimension = parameters.get("dimension");
    if (dimension != null && !Set.of("accountStatus", "userType").contains(dimension.toString())) {
      throw new IllegalArgumentException("DIMENSION_INVALID");
    }
    if ("getSu01UserDetail".equals(operation)) requiredUsername(parameters);
  }

  private static void optionalInteger(
      Map<String, Object> parameters, String key, int minimum, int maximum, String errorCode) {
    Object raw = parameters.get(key);
    if (raw == null) return;
    try {
      int value = Integer.parseInt(raw.toString());
      if (value < minimum || value > maximum) throw new NumberFormatException();
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(errorCode);
    }
  }

  private static void optionalEnum(
      Map<String, Object> parameters, String key, Set<String> allowed, String errorCode) {
    Object raw = parameters.get(key);
    if (raw == null) return;
    boolean valid = allowed.stream().anyMatch(value -> value.equalsIgnoreCase(raw.toString()));
    if (!valid) throw new IllegalArgumentException(errorCode);
  }

  private static List<Map<String, Object>> maps(List<UserRecord> users, boolean includeRisk) {
    return users.stream().map(user -> user.toMap(includeRisk)).toList();
  }

  private static List<Map<String, Object>> summarize(List<UserRecord> users, Map<String, Object> parameters) {
    String dimension = "userType".equals(parameters.get("dimension")) ? "userType" : "accountStatus";
    var counts = new java.util.TreeMap<String, Integer>();
    for (var user : users) {
      String value = "userType".equals(dimension) ? user.userType() : user.accountStatus();
      counts.merge(value, 1, Integer::sum);
    }
    var output = new ArrayList<Map<String, Object>>();
    counts.forEach((value, count) -> {
      var row = new LinkedHashMap<String, Object>();
      row.put("dimension", dimension);
      row.put("value", value);
      row.put("count", count);
      output.add(row);
    });
    output.sort(Comparator.comparing(item -> item.get("value").toString()));
    return output;
  }
}
