package com.logali.rfcguard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class GuardService {
  static final Set<String> READ_OPERATIONS = Set.of(
      "listSu01Users", "getSu01UserDetail", "listSu01RiskAccounts", "summarizeSu01Accounts",
      "listCompanyCodes", "getCompanyCodeDetail", "searchMaterials", "getMaterialDetail",
      "checkMaterialAvailability", "getPurchaseOrderDetail", "getSalesOrderStatus",
      "listIncomingInvoices", "getIncomingInvoiceDetail", "detectPotentialDuplicateInvoices",
      "getVendorOpenItems", "getCustomerOpenItems", "summarizeOverdueItems",
      "getVendorDetail", "getCustomerDetail");
  static final Set<String> WRITE_OPERATIONS = Set.of("createSu01CommunicationUser");
  private static final Map<String, Set<String>> PARAMETERS = Map.ofEntries(
      Map.entry("listSu01Users", Set.of("client", "maxRows", "inactiveDays", "userType", "accountStatus")),
      Map.entry("getSu01UserDetail", Set.of("client", "username")),
      Map.entry("listSu01RiskAccounts", Set.of("client", "maxRows", "inactiveDays")),
      Map.entry("summarizeSu01Accounts", Set.of("client", "maxRows", "inactiveDays", "dimension")),
      Map.entry("listCompanyCodes", Set.of("client")),
      Map.entry("getCompanyCodeDetail", Set.of("client", "companyCode")),
      Map.entry("searchMaterials", Set.of("client", "maxRows", "materialPattern", "descriptionPattern")),
      Map.entry("getMaterialDetail", Set.of("client", "material", "plant", "valuationArea", "valuationType")),
      Map.entry("checkMaterialAvailability", Set.of(
          "client", "maxRows", "material", "plant", "storageLocation", "requestedDate",
          "requestedQuantity", "unit", "checkRule")),
      Map.entry("getPurchaseOrderDetail", Set.of("client", "purchaseOrder")),
      Map.entry("getSalesOrderStatus", Set.of("client", "salesDocument")),
      Map.entry("listIncomingInvoices", Set.of(
          "client", "maxRows", "dateFrom", "dateTo", "vendor", "reference", "companyCode")),
      Map.entry("getIncomingInvoiceDetail", Set.of(
          "client", "invoiceDocument", "fiscalYear")),
      Map.entry("detectPotentialDuplicateInvoices", Set.of(
          "client", "maxRows", "dateFrom", "dateTo", "vendor", "reference", "amount",
          "currency", "amountTolerance")),
      Map.entry("getVendorOpenItems", Set.of(
          "client", "maxRows", "companyCode", "vendor", "keyDate", "notedItems")),
      Map.entry("getCustomerOpenItems", Set.of(
          "client", "maxRows", "companyCode", "customer", "keyDate", "notedItems")),
      Map.entry("summarizeOverdueItems", Set.of(
          "client", "maxRows", "accountType", "companyCode", "account", "keyDate", "notedItems")),
      Map.entry("getVendorDetail", Set.of("client", "vendor", "companyCode")),
      Map.entry("getCustomerDetail", Set.of("client", "customer", "companyCode")),
      Map.entry("createSu01CommunicationUser", Set.of(
          "client", "username", "firstName", "lastName", "email", "validDays")));
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
      case "listCompanyCodes", "getCompanyCodeDetail", "searchMaterials", "getMaterialDetail",
          "checkMaterialAvailability", "getPurchaseOrderDetail", "getSalesOrderStatus",
          "listIncomingInvoices", "getIncomingInvoiceDetail", "detectPotentialDuplicateInvoices",
          "getVendorOpenItems", "getCustomerOpenItems", "summarizeOverdueItems",
          "getVendorDetail", "getCustomerDetail" ->
          adapter.executeBusinessRead(operation, parameters);
      case "createSu01CommunicationUser" -> List.of(adapter.createCommunicationUser(parameters));
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
    if ("getCompanyCodeDetail".equals(operation)) {
      requiredPattern(parameters, "companyCode", "[A-Za-z0-9]{4}", "COMPANY_CODE_INVALID");
    }
    if ("searchMaterials".equals(operation)) {
      optionalPattern(parameters, "materialPattern", "[A-Za-z0-9*+._/@\\-]{1,40}",
          "MATERIAL_PATTERN_INVALID");
      optionalText(parameters, "descriptionPattern", 1, 40, "DESCRIPTION_PATTERN_INVALID");
    }
    if ("getMaterialDetail".equals(operation)) {
      requiredPattern(parameters, "material", "[A-Za-z0-9+._/@\\-]{1,40}", "MATERIAL_INVALID");
      optionalPattern(parameters, "plant", "[A-Za-z0-9]{1,4}", "PLANT_INVALID");
      optionalPattern(parameters, "valuationArea", "[A-Za-z0-9]{1,4}", "VALUATION_AREA_INVALID");
      optionalPattern(parameters, "valuationType", "[A-Za-z0-9._/@\\-]{1,10}",
          "VALUATION_TYPE_INVALID");
    }
    if ("checkMaterialAvailability".equals(operation)) {
      requiredPattern(parameters, "material", "[A-Za-z0-9+._/@\\-]{1,40}", "MATERIAL_INVALID");
      requiredPattern(parameters, "plant", "[A-Za-z0-9]{1,4}", "PLANT_INVALID");
      optionalPattern(parameters, "storageLocation", "[A-Za-z0-9]{1,4}", "STORAGE_LOCATION_INVALID");
      requiredIsoDate(parameters, "requestedDate", "REQUESTED_DATE_INVALID");
      requiredDecimal(parameters, "requestedQuantity", true, "REQUESTED_QUANTITY_INVALID");
      optionalPattern(parameters, "unit", "[A-Za-z0-9]{1,3}", "UNIT_INVALID");
      optionalPattern(parameters, "checkRule", "[A-Za-z0-9]{1,2}", "CHECK_RULE_INVALID");
    }
    if ("getPurchaseOrderDetail".equals(operation)) {
      requiredPattern(parameters, "purchaseOrder", "[0-9]{1,10}", "PURCHASE_ORDER_INVALID");
    }
    if ("getSalesOrderStatus".equals(operation)) {
      requiredPattern(parameters, "salesDocument", "[0-9]{1,10}", "SALES_DOCUMENT_INVALID");
    }
    if (Set.of("listIncomingInvoices", "detectPotentialDuplicateInvoices").contains(operation)) {
      validateDateRange(parameters, "dateFrom", "dateTo", 31);
      optionalPattern(parameters, "vendor", "[0-9]{1,10}", "VENDOR_INVALID");
      optionalText(parameters, "reference", 1, 64, "REFERENCE_INVALID");
      if (parameters.containsKey("companyCode")) {
        requiredPattern(parameters, "companyCode", "[A-Za-z0-9]{4}", "COMPANY_CODE_INVALID");
      }
    }
    if ("detectPotentialDuplicateInvoices".equals(operation)) {
      requiredPattern(parameters, "vendor", "[0-9]{1,10}", "VENDOR_INVALID");
      requiredText(parameters, "reference", 1, 64, "REFERENCE_INVALID");
      requiredDecimal(parameters, "amount", false, "AMOUNT_INVALID");
      requiredPattern(parameters, "currency", "[A-Za-z]{3}", "CURRENCY_INVALID");
      optionalDecimal(parameters, "amountTolerance", false, "AMOUNT_TOLERANCE_INVALID");
    }
    if ("getIncomingInvoiceDetail".equals(operation)) {
      requiredPattern(parameters, "invoiceDocument", "[0-9]{1,10}", "INVOICE_DOCUMENT_INVALID");
      requiredPattern(parameters, "fiscalYear", "[0-9]{4}", "FISCAL_YEAR_INVALID");
    }
    if (Set.of("getVendorOpenItems", "getCustomerOpenItems", "summarizeOverdueItems")
        .contains(operation)) {
      requiredPattern(parameters, "companyCode", "[A-Za-z0-9]{4}", "COMPANY_CODE_INVALID");
      requiredIsoDate(parameters, "keyDate", "KEY_DATE_INVALID");
      optionalBoolean(parameters, "notedItems", "NOTED_ITEMS_INVALID");
    }
    if ("getVendorOpenItems".equals(operation)) {
      requiredPattern(parameters, "vendor", "[0-9]{1,10}", "VENDOR_INVALID");
    }
    if ("getCustomerOpenItems".equals(operation)) {
      requiredPattern(parameters, "customer", "[0-9]{1,10}", "CUSTOMER_INVALID");
    }
    if ("summarizeOverdueItems".equals(operation)) {
      optionalEnum(parameters, "accountType", Set.of("vendor", "customer"), "ACCOUNT_TYPE_INVALID");
      if (!parameters.containsKey("accountType")) throw new IllegalArgumentException("ACCOUNT_TYPE_INVALID");
      requiredPattern(parameters, "account", "[0-9]{1,10}", "ACCOUNT_INVALID");
    }
    if ("getVendorDetail".equals(operation)) {
      requiredPattern(parameters, "vendor", "[0-9]{1,10}", "VENDOR_INVALID");
      requiredPattern(parameters, "companyCode", "[A-Za-z0-9]{4}", "COMPANY_CODE_INVALID");
    }
    if ("getCustomerDetail".equals(operation)) {
      requiredPattern(parameters, "customer", "[0-9]{1,10}", "CUSTOMER_INVALID");
      requiredPattern(parameters, "companyCode", "[A-Za-z0-9]{4}", "COMPANY_CODE_INVALID");
    }
    if ("createSu01CommunicationUser".equals(operation)) {
      requiredUsername(parameters);
      requiredText(parameters, "firstName", 1, 40, "FIRST_NAME_INVALID");
      requiredText(parameters, "lastName", 1, 40, "LAST_NAME_INVALID");
      optionalInteger(parameters, "validDays", 1, 30, "VALID_DAYS_INVALID");
      Object email = parameters.get("email");
      if (email != null && !email.toString().isBlank()
          && !email.toString().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
        throw new IllegalArgumentException("EMAIL_INVALID");
      }
    }
  }

  private static void requiredText(
      Map<String, Object> parameters, String key, int minimum, int maximum, String errorCode) {
    Object raw = parameters.get(key);
    if (raw == null) throw new IllegalArgumentException(errorCode);
    int length = raw.toString().trim().length();
    if (length < minimum || length > maximum) throw new IllegalArgumentException(errorCode);
  }

  private static void optionalText(
      Map<String, Object> parameters, String key, int minimum, int maximum, String errorCode) {
    Object raw = parameters.get(key);
    if (raw == null || raw.toString().isBlank()) return;
    int length = raw.toString().trim().length();
    if (length < minimum || length > maximum) throw new IllegalArgumentException(errorCode);
  }

  private static void requiredPattern(
      Map<String, Object> parameters, String key, String pattern, String errorCode) {
    Object raw = parameters.get(key);
    if (raw == null || !raw.toString().trim().matches(pattern)) {
      throw new IllegalArgumentException(errorCode);
    }
  }

  private static void optionalPattern(
      Map<String, Object> parameters, String key, String pattern, String errorCode) {
    Object raw = parameters.get(key);
    if (raw != null && !raw.toString().isBlank() && !raw.toString().trim().matches(pattern)) {
      throw new IllegalArgumentException(errorCode);
    }
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

  private static LocalDate requiredIsoDate(
      Map<String, Object> parameters, String key, String errorCode) {
    Object raw = parameters.get(key);
    try {
      if (raw == null) throw new DateTimeParseException("missing", "", 0);
      return LocalDate.parse(raw.toString().trim());
    } catch (DateTimeParseException error) {
      throw new IllegalArgumentException(errorCode);
    }
  }

  private static void validateDateRange(
      Map<String, Object> parameters, String fromKey, String toKey, int maximumDays) {
    LocalDate from = requiredIsoDate(parameters, fromKey, "DATE_RANGE_INVALID");
    LocalDate to = requiredIsoDate(parameters, toKey, "DATE_RANGE_INVALID");
    long days = ChronoUnit.DAYS.between(from, to);
    if (days < 0 || days > maximumDays) throw new IllegalArgumentException("DATE_RANGE_INVALID");
  }

  private static void requiredDecimal(
      Map<String, Object> parameters, String key, boolean positive, String errorCode) {
    Object raw = parameters.get(key);
    if (raw == null) throw new IllegalArgumentException(errorCode);
    validateDecimal(raw, positive, errorCode);
  }

  private static void optionalDecimal(
      Map<String, Object> parameters, String key, boolean positive, String errorCode) {
    Object raw = parameters.get(key);
    if (raw == null || raw.toString().isBlank()) return;
    validateDecimal(raw, positive, errorCode);
  }

  private static void validateDecimal(Object raw, boolean positive, String errorCode) {
    try {
      BigDecimal value = new BigDecimal(raw.toString().trim());
      if (value.scale() > 6 || value.precision() > 20
          || (positive ? value.signum() <= 0 : value.signum() < 0)) {
        throw new NumberFormatException();
      }
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(errorCode);
    }
  }

  private static void optionalBoolean(
      Map<String, Object> parameters, String key, String errorCode) {
    Object raw = parameters.get(key);
    if (raw == null) return;
    if (raw instanceof Boolean) return;
    if (!Set.of("true", "false").contains(raw.toString().toLowerCase())) {
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
