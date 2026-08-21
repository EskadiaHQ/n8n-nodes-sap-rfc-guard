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
  private static final String COMPANY_CODE_LIST_BAPI = "BAPI_COMPANYCODE_GETLIST";
  private static final String COMPANY_CODE_DETAIL_BAPI = "BAPI_COMPANYCODE_GETDETAIL";
  private static final String MATERIAL_LIST_BAPI = "BAPI_MATERIAL_GETLIST";
  private static final String MATERIAL_DETAIL_BAPI = "BAPI_MATERIAL_GET_DETAIL";
  private static final String PURCHASE_ORDER_DETAIL_BAPI = "BAPI_PO_GETDETAIL1";
  private static final String SALES_ORDER_STATUS_BAPI = "BAPI_SALESORDER_GETSTATUS";
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

  @Override public List<Map<String, Object>> executeBusinessRead(
      String operation, Map<String, Object> parameters) {
    assertConfiguredClient(parameters);
    return switch (operation) {
      case "listCompanyCodes" -> listCompanyCodes();
      case "getCompanyCodeDetail" -> companyCodeDetail(parameters);
      case "searchMaterials" -> searchMaterials(parameters);
      case "getMaterialDetail" -> materialDetail(parameters);
      case "getPurchaseOrderDetail" -> purchaseOrderDetail(parameters);
      case "getSalesOrderStatus" -> salesOrderStatus(parameters);
      default -> throw new IllegalArgumentException("OPERATION_NOT_ALLOWED");
    };
  }

  private List<Map<String, Object>> listCompanyCodes() {
    Object function = function(COMPANY_CODE_LIST_BAPI);
    execute(function);
    assertNoBapiErrors(function);
    Object table = table(function, "COMPANYCODE_LIST");
    return tableRows(table, configuration.maxRows(),
        "companyCode", "COMP_CODE", "name", "COMP_NAME");
  }

  private List<Map<String, Object>> companyCodeDetail(Map<String, Object> parameters) {
    Object function = function(COMPANY_CODE_DETAIL_BAPI);
    Object imports = JcoReflection.invoke(function, "getImportParameterList");
    JcoReflection.invoke(imports, "setValue", "COMPANYCODEID",
        string(parameters.get("companyCode")).toUpperCase());
    execute(function);
    assertNoBapiErrors(function);
    Object exports = JcoReflection.invoke(function, "getExportParameterList");
    Object detail = JcoReflection.invoke(exports, "getStructure", "COMPANYCODE_DETAIL");
    Object address = JcoReflection.invoke(exports, "getStructure", "COMPANYCODE_ADDRESS");
    var row = mapped(detail,
        "companyCode", "COMP_CODE", "name", "COMP_NAME", "city", "CITY",
        "country", "COUNTRY", "currency", "CURRENCY", "language", "LANGU",
        "chartOfAccounts", "CHRT_ACCTS", "fiscalYearVariant", "FY_VARIANT",
        "vatRegistrationNumber", "VAT_REG_NO", "company", "COMPANY",
        "addressNumber", "ADDR_NO", "countryIso", "COUNTRY_ISO",
        "currencyIso", "CURRENCY_ISO", "languageIso", "LANGU_ISO");
    row.putAll(mapped(address, "street", "STREET", "houseNumber", "HOUSE_NO",
        "postalCode", "POSTL_COD1", "telephone", "TEL1_NUMBR"));
    return List.of(row);
  }

  private List<Map<String, Object>> searchMaterials(Map<String, Object> parameters) {
    int limit = boundedInteger(parameters.get("maxRows"), configuration.maxRows(),
        1, configuration.maxRows(), "MAX_ROWS_INVALID");
    Object function = function(MATERIAL_LIST_BAPI);
    Object imports = JcoReflection.invoke(function, "getImportParameterList");
    JcoReflection.invoke(imports, "setValue", "MAXROWS", limit);
    String materialPattern = string(parameters.get("materialPattern"));
    if (!materialPattern.isBlank()) {
      Object selection = table(function, "MATNRSELECTION");
      JcoReflection.invoke(selection, "appendRow");
      JcoReflection.invoke(selection, "setValue", "SIGN", "I");
      JcoReflection.invoke(selection, "setValue", "OPTION", rangeOption(materialPattern));
      JcoReflection.invoke(selection, "setValue", "MATNR_LOW", materialPattern.toUpperCase());
      JcoReflection.invoke(selection, "setValue", "MATNR_LOW_LONG", materialPattern.toUpperCase());
    }
    String descriptionPattern = string(parameters.get("descriptionPattern"));
    if (!descriptionPattern.isBlank()) {
      Object selection = table(function, "MATERIALSHORTDESCSEL");
      JcoReflection.invoke(selection, "appendRow");
      JcoReflection.invoke(selection, "setValue", "SIGN", "I");
      JcoReflection.invoke(selection, "setValue", "OPTION", "CP");
      JcoReflection.invoke(selection, "setValue", "DESCR_LOW", wildcard(descriptionPattern));
    }
    execute(function);
    assertNoBapiErrors(function);
    return tableRows(table(function, "MATNRLIST"), limit,
        "material", "MATERIAL", "materialLong", "MATERIAL_LONG", "description", "MATL_DESC");
  }

  private List<Map<String, Object>> materialDetail(Map<String, Object> parameters) {
    Object function = function(MATERIAL_DETAIL_BAPI);
    Object imports = JcoReflection.invoke(function, "getImportParameterList");
    String material = string(parameters.get("material")).toUpperCase();
    JcoReflection.invoke(imports, "setValue", material.length() > 18 ? "MATERIAL_LONG" : "MATERIAL", material);
    setIfPresent(imports, "PLANT", parameters.get("plant"));
    setIfPresent(imports, "VALUATIONAREA", parameters.get("valuationArea"));
    setIfPresent(imports, "VALUATIONTYPE", parameters.get("valuationType"));
    execute(function);
    assertNoBapiErrors(function);
    Object exports = JcoReflection.invoke(function, "getExportParameterList");
    Object general = JcoReflection.invoke(exports, "getStructure", "MATERIAL_GENERAL_DATA");
    Object plant = JcoReflection.invoke(exports, "getStructure", "MATERIALPLANTDATA");
    Object valuation = JcoReflection.invoke(exports, "getStructure", "MATERIALVALUATIONDATA");
    var row = new LinkedHashMap<String, Object>();
    row.put("material", material);
    row.putAll(mapped(general,
        "description", "MATL_DESC", "materialType", "MATL_TYPE", "industrySector", "IND_SECTOR",
        "division", "DIVISION", "materialGroup", "MATL_GROUP", "baseUnit", "BASE_UOM",
        "baseUnitIso", "BASE_UOM_ISO", "grossWeight", "GROSS_WT", "netWeight", "NET_WEIGHT",
        "weightUnit", "UNIT_OF_WT", "volume", "VOLUME", "volumeUnit", "VOLUMEUNIT",
        "createdBy", "CREATED_BY", "changedBy", "CHANGED_BY"));
    row.put("createdOn", dateValue(general, "CREATED_ON"));
    row.put("lastChangedOn", dateValue(general, "LAST_CHNGE"));
    row.put("plant", string(parameters.get("plant")).toUpperCase());
    row.putAll(mapped(plant, "purchasingGroup", "PUR_GROUP", "issueUnit", "ISSUE_UNIT"));
    row.put("valuationArea", string(parameters.get("valuationArea")).toUpperCase());
    row.putAll(mapped(valuation,
        "priceControl", "PRICE_CTRL", "movingPrice", "MOVING_PR", "standardPrice", "STD_PRICE",
        "priceUnit", "PRICE_UNIT", "currency", "CURRENCY", "currencyIso", "CURRENCY_ISO"));
    return List.of(row);
  }

  private List<Map<String, Object>> purchaseOrderDetail(Map<String, Object> parameters) {
    Object function = function(PURCHASE_ORDER_DETAIL_BAPI);
    Object imports = JcoReflection.invoke(function, "getImportParameterList");
    String purchaseOrder = documentNumber(parameters.get("purchaseOrder"));
    JcoReflection.invoke(imports, "setValue", "PURCHASEORDER", purchaseOrder);
    execute(function);
    assertNoBapiErrors(function);
    Object exports = JcoReflection.invoke(function, "getExportParameterList");
    Object header = JcoReflection.invoke(exports, "getStructure", "POHEADER");
    var headerFields = mapped(header,
        "purchaseOrder", "PO_NUMBER", "companyCode", "COMP_CODE", "documentType", "DOC_TYPE",
        "status", "STATUS", "createdBy", "CREATED_BY", "vendor", "VENDOR",
        "purchasingOrganization", "PURCH_ORG", "purchasingGroup", "PUR_GROUP",
        "currency", "CURRENCY", "currencyIso", "CURRENCY_ISO", "releaseStatus", "REL_STATUS");
    headerFields.put("createdOn", dateValue(header, "CREAT_DATE"));
    headerFields.put("documentDate", dateValue(header, "DOC_DATE"));
    Object items = table(function, "POITEM");
    int count = (Integer) JcoReflection.invoke(items, "getNumRows");
    var output = new ArrayList<Map<String, Object>>(Math.min(count, configuration.maxRows()));
    for (int index = 0; index < count && output.size() < configuration.maxRows(); index++) {
      JcoReflection.invoke(items, "setRow", index);
      var row = new LinkedHashMap<String, Object>(headerFields);
      row.putAll(mapped(items,
          "itemNumber", "PO_ITEM", "deletionIndicator", "DELETE_IND", "shortText", "SHORT_TEXT",
          "material", "MATERIAL", "materialLong", "MATERIAL_LONG", "plant", "PLANT",
          "storageLocation", "STGE_LOC", "materialGroup", "MATL_GROUP", "quantity", "QUANTITY",
          "orderUnit", "PO_UNIT", "orderUnitIso", "PO_UNIT_ISO", "netPrice", "NET_PRICE",
          "priceUnit", "PRICE_UNIT", "taxCode", "TAX_CODE", "deliveryCompleted", "NO_MORE_GR",
          "finalInvoice", "FINAL_INV"));
      output.add(row);
    }
    return output.isEmpty() ? List.of(headerFields) : output;
  }

  private List<Map<String, Object>> salesOrderStatus(Map<String, Object> parameters) {
    Object function = function(SALES_ORDER_STATUS_BAPI);
    Object imports = JcoReflection.invoke(function, "getImportParameterList");
    String salesDocument = documentNumber(parameters.get("salesDocument"));
    JcoReflection.invoke(imports, "setValue", "SALESDOCUMENT", salesDocument);
    execute(function);
    assertNoBapiErrors(function);
    Object status = table(function, "STATUSINFO");
    int count = (Integer) JcoReflection.invoke(status, "getNumRows");
    var output = new ArrayList<Map<String, Object>>(Math.min(count, configuration.maxRows()));
    for (int index = 0; index < count && output.size() < configuration.maxRows(); index++) {
      JcoReflection.invoke(status, "setRow", index);
      var row = mapped(status,
          "salesDocument", "DOC_NUMBER", "customerReference", "PURCH_NO", "headerProcessingStatus", "PRC_STAT_H",
          "headerDeliveryStatus", "DLV_STAT_H", "deliveryBlock", "DLV_BLOCK", "itemNumber", "ITM_NUMBER",
          "material", "MATERIAL", "materialLong", "MATERIAL_LONG", "shortText", "SHORT_TEXT",
          "requestedQuantity", "REQ_QTY", "confirmedQuantity", "CUM_CF_QTY", "salesUnit", "SALES_UNIT",
          "netValue", "NET_VALUE", "currency", "CURRENCY", "itemDeliveryStatus", "DLV_STAT_I",
          "delivery", "DELIV_NUMB", "deliveredQuantity", "DLV_QTY", "rejectionReason", "REA_FOR_RE");
      row.put("documentDate", dateValue(status, "DOC_DATE"));
      row.put("requestedDeliveryDate", dateValue(status, "REQ_DATE_H"));
      row.put("deliveryDate", dateValue(status, "DELIV_DATE"));
      output.add(row);
    }
    return output;
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
    try {
      Object tables = JcoReflection.invoke(function, "getTableParameterList");
      Object returns = JcoReflection.invoke(tables, "getTable", "RETURN");
      int count = (Integer) JcoReflection.invoke(returns, "getNumRows");
      for (int index = 0; index < count; index++) {
        JcoReflection.invoke(returns, "setRow", index);
        assertNoBapiErrorRecord(returns, prefix);
      }
    } catch (IllegalStateException error) {
      if (error.getMessage() != null && error.getMessage().startsWith(prefix + ":")) throw error;
    } catch (RuntimeException ignored) { /* This BAPI may expose RETURN as an export structure. */ }
    try {
      Object exports = JcoReflection.invoke(function, "getExportParameterList");
      Object returns = JcoReflection.invoke(exports, "getStructure", "RETURN");
      assertNoBapiErrorRecord(returns, prefix);
    } catch (IllegalStateException error) {
      if (error.getMessage() != null && error.getMessage().startsWith(prefix + ":")) throw error;
    } catch (RuntimeException ignored) { /* A RETURN parameter is optional for governed reads. */ }
  }

  private static void assertNoBapiErrorRecord(Object record, String prefix) {
    String type = JcoReflection.string(record, "TYPE");
    if ("E".equals(type) || "A".equals(type) || "X".equals(type)) {
      throw new IllegalStateException(prefix + ": " + JcoReflection.string(record, "MESSAGE"));
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
  private static Object table(Object function, String name) {
    Object tables = JcoReflection.invoke(function, "getTableParameterList");
    return JcoReflection.invoke(tables, "getTable", name);
  }

  private static List<Map<String, Object>> tableRows(
      Object table, int limit, String... fieldMapping) {
    int count = (Integer) JcoReflection.invoke(table, "getNumRows");
    var output = new ArrayList<Map<String, Object>>(Math.min(count, limit));
    for (int index = 0; index < count && output.size() < limit; index++) {
      JcoReflection.invoke(table, "setRow", index);
      output.add(mapped(table, fieldMapping));
    }
    return output;
  }

  private static LinkedHashMap<String, Object> mapped(Object record, String... fieldMapping) {
    var output = new LinkedHashMap<String, Object>();
    for (int index = 0; index < fieldMapping.length; index += 2) {
      output.put(fieldMapping[index], optionalString(record, fieldMapping[index + 1]));
    }
    return output;
  }

  private static void setIfPresent(Object record, String field, Object rawValue) {
    String value = string(rawValue);
    if (!value.isBlank()) JcoReflection.invoke(record, "setValue", field, value.toUpperCase());
  }

  private static String rangeOption(String value) { return value.contains("*") ? "CP" : "EQ"; }
  private static String wildcard(String value) {
    return value.contains("*") ? value : "*" + value + "*";
  }
  private static String documentNumber(Object raw) {
    String value = string(raw);
    return "0".repeat(Math.max(0, 10 - value.length())) + value;
  }
  private static String dateValue(Object record, String field) {
    String raw = optionalString(record, field);
    String value = digits(raw, 8);
    if (value.isBlank() || "00000000".equals(value)) return "";
    return value.substring(0, 4) + "-" + value.substring(4, 6) + "-" + value.substring(6, 8);
  }
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
