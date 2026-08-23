package com.logali.rfcguard;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

final class JcoSapAdapter implements SapAdapter {
  private static final String USER_LIST_BAPI = "BAPI_USER_GETLIST";
  private static final String USER_DETAIL_BAPI = "BAPI_USER_GET_DETAIL";
  private static final String USER_CREATE_BAPI = "BAPI_USER_CREATE1";
  private static final String COMPANY_CODE_LIST_BAPI = "BAPI_COMPANYCODE_GETLIST";
  private static final String COMPANY_CODE_DETAIL_BAPI = "BAPI_COMPANYCODE_GETDETAIL";
  private static final String MATERIAL_LIST_BAPI = "BAPI_MATERIAL_GETLIST";
  private static final String MATERIAL_DETAIL_BAPI = "BAPI_MATERIAL_GET_DETAIL";
  private static final String MATERIAL_AVAILABILITY_BAPI = "BAPI_MATERIAL_AVAILABILITY";
  private static final String PURCHASE_ORDER_DETAIL_BAPI = "BAPI_PO_GETDETAIL1";
  private static final String SALES_ORDER_STATUS_BAPI = "BAPI_SALESORDER_GETSTATUS";
  private static final String INCOMING_INVOICE_LIST_BAPI = "BAPI_INCOMINGINVOICE_GETLIST";
  private static final String INCOMING_INVOICE_DETAIL_BAPI = "BAPI_INCOMINGINVOICE_GETDETAIL";
  private static final String VENDOR_OPEN_ITEMS_BAPI = "BAPI_AP_ACC_GETOPENITEMS";
  private static final String CUSTOMER_OPEN_ITEMS_BAPI = "BAPI_AR_ACC_GETOPENITEMS";
  private static final String VENDOR_DETAIL_BAPI = "BAPI_VENDOR_GETDETAIL";
  private static final String CUSTOMER_DETAIL_BAPI = "BAPI_CUSTOMER_GETDETAIL2";
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
    if (!configuration.usesManagedDestination()) {
      JcoReflection.registerProvider(configuration.destinationName(), properties);
    }
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
      case "checkMaterialAvailability" -> materialAvailability(parameters);
      case "getPurchaseOrderDetail" -> purchaseOrderDetail(parameters);
      case "getSalesOrderStatus" -> salesOrderStatus(parameters);
      case "listIncomingInvoices" -> incomingInvoices(parameters, true);
      case "getIncomingInvoiceDetail" -> incomingInvoiceDetail(parameters);
      case "detectPotentialDuplicateInvoices" -> duplicateInvoices(parameters);
      case "getVendorOpenItems" -> openItems(parameters, true);
      case "getCustomerOpenItems" -> openItems(parameters, false);
      case "summarizeOverdueItems" -> overdueSummary(parameters);
      case "getVendorDetail" -> vendorDetail(parameters);
      case "getCustomerDetail" -> customerDetail(parameters);
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

  private List<Map<String, Object>> materialAvailability(Map<String, Object> parameters) {
    int limit = boundedInteger(parameters.get("maxRows"), configuration.maxRows(),
        1, configuration.maxRows(), "MAX_ROWS_INVALID");
    Object function = function(MATERIAL_AVAILABILITY_BAPI);
    Object imports = JcoReflection.invoke(function, "getImportParameterList");
    String material = string(parameters.get("material")).toUpperCase();
    JcoReflection.invoke(imports, "setValue", material.length() > 18 ? "MATERIAL_LONG" : "MATERIAL", material);
    JcoReflection.invoke(imports, "setValue", "PLANT", string(parameters.get("plant")).toUpperCase());
    setIfPresent(imports, "STGE_LOC", parameters.get("storageLocation"));
    setIfPresent(imports, "UNIT", parameters.get("unit"));
    setIfPresent(imports, "CHECK_RULE", parameters.get("checkRule"));
    Object requested = table(function, "WMDVSX");
    JcoReflection.invoke(requested, "appendRow");
    JcoReflection.invoke(requested, "setValue", "REQ_DATE", sapDate(parameters.get("requestedDate")));
    JcoReflection.invoke(requested, "setValue", "REQ_QTY", string(parameters.get("requestedQuantity")));
    execute(function);
    assertNoBapiErrors(function);

    Object exports = JcoReflection.invoke(function, "getExportParameterList");
    String availableAtPlant = optionalString(exports, "AV_QTY_PLT");
    String dialogFlag = optionalString(exports, "DIALOGFLAG");
    String endLeadTime = dateValue(exports, "ENDLEADTME");
    Object confirmations = table(function, "WMDVEX");
    int count = (Integer) JcoReflection.invoke(confirmations, "getNumRows");
    var output = new ArrayList<Map<String, Object>>(Math.min(Math.max(count, 1), limit));
    for (int index = 0; index < count && output.size() < limit; index++) {
      JcoReflection.invoke(confirmations, "setRow", index);
      var row = new LinkedHashMap<String, Object>();
      row.put("material", material);
      row.put("plant", string(parameters.get("plant")).toUpperCase());
      row.put("storageLocation", string(parameters.get("storageLocation")).toUpperCase());
      row.put("unit", string(parameters.get("unit")).toUpperCase());
      row.put("requestedDate", dateValue(confirmations, "REQ_DATE"));
      String requestedQuantity = optionalString(confirmations, "REQ_QTY");
      String confirmedQuantity = optionalString(confirmations, "COM_QTY");
      row.put("requestedQuantity", requestedQuantity);
      row.put("confirmedDate", dateValue(confirmations, "COM_DATE"));
      row.put("confirmedQuantity", confirmedQuantity);
      row.put("availableQuantityAtPlant", availableAtPlant);
      row.put("availabilityStatus",
          availabilityStatus(dialogFlag, requestedQuantity, confirmedQuantity));
      row.put("endLeadTimeDate", endLeadTime);
      output.add(row);
    }
    if (output.isEmpty()) {
      var row = new LinkedHashMap<String, Object>();
      row.put("material", material);
      row.put("plant", string(parameters.get("plant")).toUpperCase());
      row.put("storageLocation", string(parameters.get("storageLocation")).toUpperCase());
      row.put("unit", string(parameters.get("unit")).toUpperCase());
      row.put("requestedDate", string(parameters.get("requestedDate")));
      row.put("requestedQuantity", string(parameters.get("requestedQuantity")));
      row.put("confirmedDate", "");
      row.put("confirmedQuantity", "0");
      row.put("availableQuantityAtPlant", availableAtPlant);
      row.put("availabilityStatus",
          availabilityStatus(dialogFlag, parameters.get("requestedQuantity"), "0"));
      row.put("endLeadTimeDate", endLeadTime);
      output.add(row);
    }
    return output;
  }

  private List<Map<String, Object>> incomingInvoices(
      Map<String, Object> parameters, boolean applyReferenceFilter) {
    int limit = boundedInteger(parameters.get("maxRows"), configuration.maxRows(),
        1, configuration.maxRows(), "MAX_ROWS_INVALID");
    Object function = function(INCOMING_INVOICE_LIST_BAPI);
    Object imports = JcoReflection.invoke(function, "getImportParameterList");
    JcoReflection.invoke(imports, "setValue", "ERP_DOCUMENTS", "X");
    appendRange(function, "DOCDATE_RA", "BT", sapDate(parameters.get("dateFrom")),
        sapDate(parameters.get("dateTo")));
    String vendor = string(parameters.get("vendor"));
    if (!vendor.isBlank()) appendRange(function, "VENDOR_RA", "EQ", accountNumber(vendor), "");
    String reference = string(parameters.get("reference"));
    if (applyReferenceFilter && !reference.isBlank()) {
      appendRange(function, "REFDOC_RA", "EQ", reference, "");
    }
    execute(function);
    assertNoBapiErrors(function);

    String companyCode = string(parameters.get("companyCode")).toUpperCase();
    Object headers = table(function, "HEADERLIST");
    int count = (Integer) JcoReflection.invoke(headers, "getNumRows");
    var output = new ArrayList<Map<String, Object>>(Math.min(count, limit));
    for (int index = 0; index < count && output.size() < limit; index++) {
      JcoReflection.invoke(headers, "setRow", index);
      if (!companyCode.isBlank() && !companyCode.equalsIgnoreCase(optionalString(headers, "COMP_CODE"))) {
        continue;
      }
      var row = mapped(headers,
          "invoiceDocument", "INV_DOC_NO", "fiscalYear", "FISC_YEAR",
          "companyCode", "COMP_CODE", "grossAmount", "GROSS_AMNT",
          "currency", "CURRENCY", "currencyIso", "CURRENCY_ISO",
          "differentInvoicingParty", "DIFF_INV", "reference", "REF_DOC_NO",
          "referenceLong", "REF_DOC_NO_LONG", "headerText", "HEADER_TXT",
          "enteredBy", "PERSON_EXT", "invoiceStatus", "INVOICE_STATUS",
          "transaction", "INV_TRAN_");
      row.put("vendor", vendor.isBlank() ? "" : accountNumber(vendor));
      row.put("documentDate", dateValue(headers, "DOC_DATE"));
      row.put("postingDate", dateValue(headers, "PSTNG_DATE"));
      row.put("entryDate", dateValue(headers, "ENTRY_DATE"));
      output.add(row);
    }
    return output;
  }

  private List<Map<String, Object>> incomingInvoiceDetail(Map<String, Object> parameters) {
    Object function = function(INCOMING_INVOICE_DETAIL_BAPI);
    Object imports = JcoReflection.invoke(function, "getImportParameterList");
    JcoReflection.invoke(imports, "setValue", "INVOICEDOCNUMBER",
        documentNumber(parameters.get("invoiceDocument")));
    JcoReflection.invoke(imports, "setValue", "FISCALYEAR", string(parameters.get("fiscalYear")));
    execute(function);
    assertNoBapiErrors(function);
    Object exports = JcoReflection.invoke(function, "getExportParameterList");
    Object header = JcoReflection.invoke(exports, "getStructure", "HEADERDATA");
    var headerFields = mapped(header,
        "invoiceDocument", "INV_DOC_NO", "fiscalYear", "FISC_YEAR",
        "invoiceIndicator", "INVOICE_IND", "documentType", "DOC_TYPE",
        "username", "USERNAME", "reference", "REF_DOC_NO", "referenceLong", "REF_DOC_NO_LONG",
        "companyCode", "COMP_CODE", "differentInvoicingParty", "DIFF_INV",
        "currency", "CURRENCY", "currencyIso", "CURRENCY_ISO", "grossAmount", "GROSS_AMNT",
        "paymentTerms", "PMNTTRMS", "netTermsDays", "NETTERMS", "paymentBlock", "PMNT_BLOCK",
        "invoiceStatus", "INVOICE_STATUS");
    headerFields.put("documentDate", dateValue(header, "DOC_DATE"));
    headerFields.put("postingDate", dateValue(header, "PSTNG_DATE"));
    headerFields.put("baselineDate", dateValue(header, "BLINE_DATE"));
    headerFields.put("entryDate", dateValue(header, "ENTRY_DATE"));
    headerFields.put("invoiceReceiptDate", dateValue(header, "INV_REC_DATE"));
    Object items = table(function, "ITEMDATA");
    int count = (Integer) JcoReflection.invoke(items, "getNumRows");
    var output = new ArrayList<Map<String, Object>>(Math.min(count, configuration.maxRows()));
    for (int index = 0; index < count && output.size() < configuration.maxRows(); index++) {
      JcoReflection.invoke(items, "setRow", index);
      var row = new LinkedHashMap<String, Object>(headerFields);
      row.putAll(mapped(items,
          "invoiceItem", "INVOICE_DOC_ITEM", "purchaseOrder", "PO_NUMBER",
          "purchaseOrderItem", "PO_ITEM", "referenceDocument", "REF_DOC",
          "referenceDocumentYear", "REF_DOC_YEAR", "referenceDocumentItem", "REF_DOC_IT",
          "taxCode", "TAX_CODE", "itemAmount", "ITEM_AMOUNT", "quantity", "QUANTITY",
          "purchaseOrderUnit", "PO_UNIT", "purchaseOrderUnitIso", "PO_UNIT_ISO",
          "invoicedQuantity", "QTY_INVCD", "invoiceAmount", "INV_AMNTFC",
          "itemText", "ITEM_TEXT", "finalInvoice", "FINAL_INV",
          "debitCreditIndicator", "DEBIT_CREDIT_INDICATOR"));
      output.add(row);
    }
    return output.isEmpty() ? List.of(headerFields) : output;
  }

  private List<Map<String, Object>> duplicateInvoices(Map<String, Object> parameters) {
    List<Map<String, Object>> candidates = incomingInvoices(parameters, false);
    String expectedReference = normalizedReference(string(parameters.get("reference")));
    BigDecimal expectedAmount = decimal(parameters.get("amount"));
    BigDecimal tolerance = parameters.containsKey("amountTolerance")
        ? decimal(parameters.get("amountTolerance")) : new BigDecimal("0.01");
    String expectedCurrency = string(parameters.get("currency")).toUpperCase();
    var output = new ArrayList<Map<String, Object>>();
    for (Map<String, Object> candidate : candidates) {
      String actualReference = normalizedReference(firstNonBlank(
          string(candidate.get("referenceLong")), string(candidate.get("reference"))));
      BigDecimal actualAmount = decimal(candidate.get("grossAmount"));
      String actualCurrency = string(candidate.get("currency")).toUpperCase();
      boolean referenceMatch = expectedReference.equals(actualReference);
      boolean amountMatch = expectedAmount.subtract(actualAmount).abs().compareTo(tolerance) <= 0;
      boolean currencyMatch = expectedCurrency.equals(actualCurrency);
      if (!referenceMatch || !amountMatch || !currencyMatch) continue;
      var row = new LinkedHashMap<String, Object>(candidate);
      row.put("potentialDuplicate", true);
      row.put("matchScore", 100);
      row.put("matchReasons", "same_vendor_reference_amount_currency_within_date_window");
      row.put("amountDifference", expectedAmount.subtract(actualAmount).abs().toPlainString());
      output.add(row);
    }
    return output;
  }

  private List<Map<String, Object>> openItems(Map<String, Object> parameters, boolean vendorAccount) {
    int limit = boundedInteger(parameters.get("maxRows"), configuration.maxRows(),
        1, configuration.maxRows(), "MAX_ROWS_INVALID");
    Object function = function(vendorAccount ? VENDOR_OPEN_ITEMS_BAPI : CUSTOMER_OPEN_ITEMS_BAPI);
    Object imports = JcoReflection.invoke(function, "getImportParameterList");
    String accountKey = vendorAccount ? "vendor" : "customer";
    String importKey = vendorAccount ? "VENDOR" : "CUSTOMER";
    String account = accountNumber(string(parameters.get(accountKey)));
    JcoReflection.invoke(imports, "setValue", "COMPANYCODE",
        string(parameters.get("companyCode")).toUpperCase());
    JcoReflection.invoke(imports, "setValue", importKey, account);
    JcoReflection.invoke(imports, "setValue", "KEYDATE", sapDate(parameters.get("keyDate")));
    if (booleanValue(parameters.get("notedItems"))) {
      JcoReflection.invoke(imports, "setValue", "NOTEDITEMS", "X");
    }
    execute(function);
    assertNoBapiErrors(function);
    LocalDate keyDate = LocalDate.parse(string(parameters.get("keyDate")));
    Object items = table(function, "LINEITEMS");
    int count = (Integer) JcoReflection.invoke(items, "getNumRows");
    var output = new ArrayList<Map<String, Object>>(Math.min(count, limit));
    for (int index = 0; index < count && output.size() < limit; index++) {
      JcoReflection.invoke(items, "setRow", index);
      var row = mapped(items,
          "companyCode", "COMP_CODE", "fiscalYear", "FISC_YEAR", "documentNumber", "DOC_NO",
          "itemNumber", "ITEM_NUM", "specialGlIndicator", "SP_GL_IND",
          "currency", "CURRENCY", "localCurrency", "LOC_CURRCY",
          "reference", "REF_DOC_NO", "referenceLong", "REF_DOC_NO_LONG",
          "documentType", "DOC_TYPE", "debitCreditIndicator", "DB_CR_IND",
          "localAmount", "LC_AMOUNT", "documentAmount", "AMT_DOCCUR",
          "openAmount", "AMOUNT", "netAmount", "NET_AMOUNT", "itemText", "ITEM_TEXT",
          "paymentTerms", "PMNTTRMS", "netTermsDays", "NETTERMS",
          "paymentMethod", "PYMT_METH", "paymentBlock", "PMNT_BLOCK",
          "invoiceReference", "INV_REF", "invoiceYear", "INV_YEAR",
          "dunningBlock", "DUNN_BLOCK", "dunningLevel", "DUNN_LEVEL",
          "name", "NAME", "country", "COUNTRY");
      row.put(vendorAccount ? "vendor" : "customer", account);
      row.put("postingDate", dateValue(items, "PSTNG_DATE"));
      row.put("documentDate", dateValue(items, "DOC_DATE"));
      row.put("entryDate", dateValue(items, "ENTRY_DATE"));
      String baselineDate = dateValue(items, "BLINE_DATE");
      row.put("baselineDate", baselineDate);
      LocalDate dueDate = dueDate(baselineDate, optionalString(items, "NETTERMS"));
      int daysOverdue = dueDate == null ? 0 : (int) Math.max(0, ChronoUnit.DAYS.between(dueDate, keyDate));
      row.put("dueDate", dueDate == null ? "" : dueDate.toString());
      row.put("daysOverdue", daysOverdue);
      row.put("overdue", daysOverdue > 0);
      output.add(row);
    }
    return output;
  }

  private List<Map<String, Object>> overdueSummary(Map<String, Object> parameters) {
    boolean vendor = "vendor".equalsIgnoreCase(string(parameters.get("accountType")));
    var openParameters = new LinkedHashMap<String, Object>(parameters);
    openParameters.remove("accountType");
    Object account = openParameters.remove("account");
    openParameters.put(vendor ? "vendor" : "customer", account);
    List<Map<String, Object>> items = openItems(openParameters, vendor);
    var summaries = new LinkedHashMap<String, OverdueTotals>();
    for (Map<String, Object> item : items) {
      String currency = string(item.get("currency")).toUpperCase();
      OverdueTotals totals = summaries.computeIfAbsent(currency, ignored -> new OverdueTotals());
      totals.add(decimal(firstNonBlank(string(item.get("openAmount")), string(item.get("documentAmount")))),
          Integer.parseInt(item.get("daysOverdue").toString()));
    }
    if (summaries.isEmpty()) summaries.put("", new OverdueTotals());
    var output = new ArrayList<Map<String, Object>>();
    summaries.forEach((currency, totals) -> output.add(totals.toMap(
        vendor ? "vendor" : "customer", accountNumber(string(account)),
        string(parameters.get("companyCode")).toUpperCase(), string(parameters.get("keyDate")), currency)));
    return output;
  }

  private List<Map<String, Object>> vendorDetail(Map<String, Object> parameters) {
    Object function = function(VENDOR_DETAIL_BAPI);
    Object imports = JcoReflection.invoke(function, "getImportParameterList");
    JcoReflection.invoke(imports, "setValue", "VENDORNO", accountNumber(string(parameters.get("vendor"))));
    JcoReflection.invoke(imports, "setValue", "COMPANYCODE", string(parameters.get("companyCode")).toUpperCase());
    execute(function);
    assertNoBapiErrors(function);
    Object exports = JcoReflection.invoke(function, "getExportParameterList");
    Object general = JcoReflection.invoke(exports, "getStructure", "GENERALDETAIL");
    Object company = JcoReflection.invoke(exports, "getStructure", "COMPANYDETAIL");
    var row = mapped(general,
        "vendor", "VENDOR", "name", "NAME", "name2", "NAME_2", "city", "CITY",
        "postalCode", "POSTL_CODE", "region", "REGION", "street", "STREET",
        "country", "COUNTRY", "countryIso", "COUNTRYISO", "language", "LANGU",
        "languageIso", "LANGU_ISO", "telephone", "TELEPHONE");
    row.putAll(mapped(company,
        "companyCode", "COMP_CODE", "accountingClerk", "CLERK", "paymentTerms", "PMNTTRMS",
        "paymentMethods", "PAYMENT_METHODS", "companyTelephone", "TEL", "companyFax", "FAX"));
    return List.of(row);
  }

  private List<Map<String, Object>> customerDetail(Map<String, Object> parameters) {
    Object function = function(CUSTOMER_DETAIL_BAPI);
    Object imports = JcoReflection.invoke(function, "getImportParameterList");
    JcoReflection.invoke(imports, "setValue", "CUSTOMERNO", accountNumber(string(parameters.get("customer"))));
    JcoReflection.invoke(imports, "setValue", "COMPANYCODE", string(parameters.get("companyCode")).toUpperCase());
    execute(function);
    assertNoBapiErrors(function);
    Object exports = JcoReflection.invoke(function, "getExportParameterList");
    Object address = JcoReflection.invoke(exports, "getStructure", "CUSTOMERADDRESS");
    Object general = JcoReflection.invoke(exports, "getStructure", "CUSTOMERGENERALDETAIL");
    Object company = JcoReflection.invoke(exports, "getStructure", "CUSTOMERCOMPANYDETAIL");
    var row = mapped(address,
        "customer", "CUSTOMER", "name", "NAME", "name2", "NAME_2", "city", "CITY",
        "postalCode", "POSTL_CODE", "region", "REGION", "street", "STREET",
        "telephone", "TELEPHONE", "country", "COUNTRY", "countryIso", "COUNTRYISO",
        "language", "LANGU", "languageIso", "LANGU_ISO");
    row.putAll(mapped(general,
        "accountGroup", "ACCNT_GRP", "orderBlock", "ORDR_BLK_G", "deliveryBlock", "DELI_BLK_G",
        "billingBlock", "BILL_BLK_G", "postingBlock", "PSTG_BLK_G", "deletionFlag", "DEL_FLAG_G",
        "paymentBlock", "PMNT_BLOCK", "industry", "INDUSTRY", "vatRegistrationNumber", "VAT_REG_NO"));
    row.put("createdOn", dateValue(general, "CREAT_DATE"));
    row.putAll(mapped(company,
        "companyCode", "COMP_CODE", "accountingClerk", "CLERK", "paymentTerms", "PMNTTRMS",
        "paymentMethods", "PAYMENT_METHODS", "companyTelephone", "TEL", "companyFax", "FAX"));
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
    Map<String, PurchaseOrderFacts> factsByItem = purchaseOrderFacts(function);
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
      PurchaseOrderFacts facts = factsByItem.getOrDefault(optionalString(items, "PO_ITEM"),
          new PurchaseOrderFacts());
      BigDecimal orderedQuantity = decimal(optionalString(items, "QUANTITY"));
      BigDecimal openDeliveryQuantity = nonNegative(orderedQuantity.subtract(facts.receivedQuantity));
      BigDecimal openInvoiceQuantity = nonNegative(facts.receivedQuantity.subtract(facts.invoicedQuantity));
      row.put("scheduleLineCount", facts.scheduleLineCount);
      row.put("earliestScheduledDeliveryDate", isoDate(facts.earliestScheduledDeliveryDate));
      row.put("latestScheduledDeliveryDate", isoDate(facts.latestScheduledDeliveryDate));
      row.put("scheduledQuantity", facts.scheduledQuantity.toPlainString());
      row.put("committedDate", isoDate(facts.latestCommittedDate));
      row.put("committedQuantity", facts.committedQuantity.toPlainString());
      row.put("confirmationLineCount", facts.confirmationLineCount);
      row.put("latestSupplierConfirmedDate", isoDate(facts.latestSupplierConfirmedDate));
      row.put("supplierConfirmedQuantity", facts.supplierConfirmedQuantity.toPlainString());
      row.put("historyEntryCount", facts.historyEntryCount);
      row.put("lastGoodsReceiptDate", isoDate(facts.lastGoodsReceiptDate));
      row.put("lastInvoiceReceiptDate", isoDate(facts.lastInvoiceReceiptDate));
      row.put("receivedQuantity", facts.receivedQuantity.toPlainString());
      row.put("invoicedQuantity", facts.invoicedQuantity.toPlainString());
      row.put("openDeliveryQuantity", openDeliveryQuantity.toPlainString());
      row.put("openInvoiceQuantity", openInvoiceQuantity.toPlainString());
      row.put("deliveryStatus", deliveryStatus(
          optionalString(items, "DELETE_IND"), optionalString(items, "NO_MORE_GR"),
          openDeliveryQuantity, facts.latestScheduledDeliveryDate, LocalDate.now(clock)));
      row.put("invoiceStatus", invoiceStatus(
          optionalString(items, "FINAL_INV"), facts.receivedQuantity, facts.invoicedQuantity));
      output.add(row);
    }
    return output.isEmpty() ? List.of(headerFields) : output;
  }

  private Map<String, PurchaseOrderFacts> purchaseOrderFacts(Object function) {
    var facts = new LinkedHashMap<String, PurchaseOrderFacts>();
    Object schedules = table(function, "POSCHEDULE");
    int scheduleCount = (Integer) JcoReflection.invoke(schedules, "getNumRows");
    for (int index = 0; index < scheduleCount; index++) {
      JcoReflection.invoke(schedules, "setRow", index);
      PurchaseOrderFacts item = facts.computeIfAbsent(optionalString(schedules, "PO_ITEM"),
          ignored -> new PurchaseOrderFacts());
      item.scheduleLineCount++;
      item.scheduledQuantity = item.scheduledQuantity.add(decimal(optionalString(schedules, "QUANTITY")));
      item.committedQuantity = item.committedQuantity.add(decimal(optionalString(schedules, "COM_QTY")));
      item.earliestScheduledDeliveryDate = earlier(
          item.earliestScheduledDeliveryDate, localDateValue(schedules, "DELIVERY_DATE"));
      item.latestScheduledDeliveryDate = later(
          item.latestScheduledDeliveryDate, localDateValue(schedules, "DELIVERY_DATE"));
      item.latestCommittedDate = later(item.latestCommittedDate, localDateValue(schedules, "COM_DATE"));
    }

    Object confirmations = table(function, "POCONFIRMATION");
    int confirmationCount = (Integer) JcoReflection.invoke(confirmations, "getNumRows");
    for (int index = 0; index < confirmationCount; index++) {
      JcoReflection.invoke(confirmations, "setRow", index);
      PurchaseOrderFacts item = facts.computeIfAbsent(optionalString(confirmations, "PO_ITEM"),
          ignored -> new PurchaseOrderFacts());
      if (!optionalString(confirmations, "DELETE_IND").isBlank()) continue;
      item.confirmationLineCount++;
      item.supplierConfirmedQuantity = item.supplierConfirmedQuantity.add(
          decimal(optionalString(confirmations, "QUANTITY")));
      item.latestSupplierConfirmedDate = later(
          item.latestSupplierConfirmedDate, localDateValue(confirmations, "DELIV_DATE"));
    }

    Object history = table(function, "POHISTORY");
    int historyCount = (Integer) JcoReflection.invoke(history, "getNumRows");
    for (int index = 0; index < historyCount; index++) {
      JcoReflection.invoke(history, "setRow", index);
      PurchaseOrderFacts item = facts.computeIfAbsent(optionalString(history, "PO_ITEM"),
          ignored -> new PurchaseOrderFacts());
      item.historyEntryCount++;
      String processId = optionalString(history, "PROCESS_ID");
      LocalDate postingDate = localDateValue(history, "PSTNG_DATE");
      if ("1".equals(processId)) item.lastGoodsReceiptDate = later(item.lastGoodsReceiptDate, postingDate);
      if (Set.of("2", "3").contains(processId)) {
        item.lastInvoiceReceiptDate = later(item.lastInvoiceReceiptDate, postingDate);
      }
    }

    Object totals = table(function, "POHISTORY_TOTALS");
    int totalCount = (Integer) JcoReflection.invoke(totals, "getNumRows");
    for (int index = 0; index < totalCount; index++) {
      JcoReflection.invoke(totals, "setRow", index);
      PurchaseOrderFacts item = facts.computeIfAbsent(optionalString(totals, "PO_ITEM"),
          ignored -> new PurchaseOrderFacts());
      item.receivedQuantity = item.receivedQuantity.add(decimal(optionalString(totals, "DELIV_QTY")));
      item.invoicedQuantity = item.invoicedQuantity.add(decimal(optionalString(totals, "IV_QTY")));
    }
    return facts;
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
    if (configured.isBlank() && configuration.usesManagedDestination()) {
      configured = backend().client();
    }
    assertClientMatches(requested.toString(), configured);
  }

  static void assertClientMatches(String requested, String configured) {
    if (!requested.equals(configured)) throw new IllegalArgumentException("CLIENT_MISMATCH");
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
  private static String accountNumber(String value) {
    return "0".repeat(Math.max(0, 10 - value.length())) + value;
  }
  private static String sapDate(Object raw) {
    return string(raw).replace("-", "");
  }
  private static void appendRange(
      Object function, String tableName, String option, String low, String high) {
    Object range = table(function, tableName);
    JcoReflection.invoke(range, "appendRow");
    JcoReflection.invoke(range, "setValue", "SIGN", "I");
    JcoReflection.invoke(range, "setValue", "OPTION", option);
    JcoReflection.invoke(range, "setValue", "LOW", low);
    if (!high.isBlank()) JcoReflection.invoke(range, "setValue", "HIGH", high);
  }
  private static boolean booleanValue(Object raw) {
    return raw instanceof Boolean value ? value : "true".equalsIgnoreCase(string(raw));
  }
  static String availabilityStatus(
      String dialogFlag, Object requestedQuantity, Object confirmedQuantity) {
    if ("N".equals(dialogFlag)) return "NotAvailabilityRelevant";
    BigDecimal requested = nonNegative(decimal(requestedQuantity));
    BigDecimal confirmed = nonNegative(decimal(confirmedQuantity));
    if (requested.signum() == 0) return "NoQuantityRequested";
    if (confirmed.compareTo(requested) >= 0) return "FullyAvailable";
    if (confirmed.signum() == 0) return "NotAvailable";
    return "PartiallyAvailable";
  }
  private static String normalizedReference(String value) {
    return value.toUpperCase().replaceAll("[^A-Z0-9]", "");
  }
  private static BigDecimal decimal(Object raw) {
    String value = string(raw).replace(",", "");
    if (value.isBlank()) return BigDecimal.ZERO;
    try { return new BigDecimal(value); }
    catch (NumberFormatException ignored) { return BigDecimal.ZERO; }
  }
  private static BigDecimal nonNegative(BigDecimal value) {
    return value.signum() < 0 ? BigDecimal.ZERO : value;
  }
  private static LocalDate dueDate(String baselineDate, String netTerms) {
    if (baselineDate.isBlank()) return null;
    try { return LocalDate.parse(baselineDate).plusDays(Integer.parseInt(netTerms.trim())); }
    catch (RuntimeException ignored) { return LocalDate.parse(baselineDate); }
  }
  private static LocalDate localDateValue(Object record, String field) {
    String value = dateValue(record, field);
    return value.isBlank() ? null : LocalDate.parse(value);
  }
  private static LocalDate earlier(LocalDate current, LocalDate candidate) {
    if (candidate == null) return current;
    return current == null || candidate.isBefore(current) ? candidate : current;
  }
  private static LocalDate later(LocalDate current, LocalDate candidate) {
    if (candidate == null) return current;
    return current == null || candidate.isAfter(current) ? candidate : current;
  }
  private static String isoDate(LocalDate value) { return value == null ? "" : value.toString(); }
  private static String deliveryStatus(
      String deletionIndicator, String deliveryCompleted, BigDecimal openQuantity,
      LocalDate latestScheduledDate, LocalDate today) {
    if (!deletionIndicator.isBlank()) return "Deleted";
    if ("X".equalsIgnoreCase(deliveryCompleted) || openQuantity.signum() == 0) return "Complete";
    if (latestScheduledDate != null && latestScheduledDate.isBefore(today)) return "Overdue";
    return "Open";
  }
  private static String invoiceStatus(
      String finalInvoice, BigDecimal receivedQuantity, BigDecimal invoicedQuantity) {
    if ("X".equalsIgnoreCase(finalInvoice)) return "Final";
    if (receivedQuantity.signum() == 0) return "NoReceipt";
    if (invoicedQuantity.compareTo(receivedQuantity) >= 0) return "Complete";
    if (invoicedQuantity.signum() > 0) return "PartiallyInvoiced";
    return "PendingInvoice";
  }
  private static String dateValue(Object record, String field) {
    return normalizeDate(optionalString(record, field));
  }
  static String normalizeDate(String raw) {
    String value = string(raw);
    if (value.isBlank() || value.matches("0{4}[-./]?0{2}[-./]?0{2}")) return "";
    for (DateTimeFormatter formatter : List.of(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.BASIC_ISO_DATE,
        DateTimeFormatter.ofPattern("dd.MM.uuuu"),
        DateTimeFormatter.ofPattern("MM/dd/uuuu"),
        DateTimeFormatter.ofPattern("dd/MM/uuuu"))) {
      try { return LocalDate.parse(value, formatter).toString(); }
      catch (RuntimeException ignored) { /* Try the next SAP/JCo display format. */ }
    }
    return "";
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

  private static final class PurchaseOrderFacts {
    int scheduleLineCount;
    int confirmationLineCount;
    int historyEntryCount;
    BigDecimal scheduledQuantity = BigDecimal.ZERO;
    BigDecimal committedQuantity = BigDecimal.ZERO;
    BigDecimal supplierConfirmedQuantity = BigDecimal.ZERO;
    BigDecimal receivedQuantity = BigDecimal.ZERO;
    BigDecimal invoicedQuantity = BigDecimal.ZERO;
    LocalDate earliestScheduledDeliveryDate;
    LocalDate latestScheduledDeliveryDate;
    LocalDate latestCommittedDate;
    LocalDate latestSupplierConfirmedDate;
    LocalDate lastGoodsReceiptDate;
    LocalDate lastInvoiceReceiptDate;
  }

  private static final class OverdueTotals {
    int itemCount;
    int overdueItemCount;
    BigDecimal total = BigDecimal.ZERO;
    BigDecimal overdue = BigDecimal.ZERO;
    BigDecimal current = BigDecimal.ZERO;
    BigDecimal days1To30 = BigDecimal.ZERO;
    BigDecimal days31To60 = BigDecimal.ZERO;
    BigDecimal days61To90 = BigDecimal.ZERO;
    BigDecimal daysOver90 = BigDecimal.ZERO;

    void add(BigDecimal amount, int daysOverdue) {
      itemCount++;
      total = total.add(amount);
      if (daysOverdue <= 0) {
        current = current.add(amount);
        return;
      }
      overdueItemCount++;
      overdue = overdue.add(amount);
      if (daysOverdue <= 30) days1To30 = days1To30.add(amount);
      else if (daysOverdue <= 60) days31To60 = days31To60.add(amount);
      else if (daysOverdue <= 90) days61To90 = days61To90.add(amount);
      else daysOver90 = daysOver90.add(amount);
    }

    Map<String, Object> toMap(
        String accountType, String account, String companyCode, String keyDate, String currency) {
      var row = new LinkedHashMap<String, Object>();
      row.put("accountType", accountType);
      row.put("account", account);
      row.put("companyCode", companyCode);
      row.put("keyDate", keyDate);
      row.put("currency", currency);
      row.put("openItemCount", itemCount);
      row.put("overdueItemCount", overdueItemCount);
      row.put("totalOpenAmount", total.toPlainString());
      row.put("overdueAmount", overdue.toPlainString());
      row.put("currentAmount", current.toPlainString());
      row.put("overdue1To30Amount", days1To30.toPlainString());
      row.put("overdue31To60Amount", days31To60.toPlainString());
      row.put("overdue61To90Amount", days61To90.toPlainString());
      row.put("overdueOver90Amount", daysOver90.toPlainString());
      return row;
    }
  }
}
