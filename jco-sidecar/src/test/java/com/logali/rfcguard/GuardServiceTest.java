package com.logali.rfcguard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class GuardServiceTest {
  @Test void exposesOnlyBusinessAliases() {
    var service = new GuardService(new StubAdapter());
    assertThrows(IllegalArgumentException.class, () -> service.execute("BAPI_USER_GETLIST", Map.of()));
  }

  @Test void summarizesWithoutReturningRawUsers() {
    var rows = new GuardService(new StubAdapter()).execute("summarizeSu01Accounts", Map.of());
    assertEquals(2, rows.size());
    assertEquals("accountStatus", rows.getFirst().get("dimension"));
  }

  @Test void returnsOnlyRiskAccounts() {
    var rows = new GuardService(new StubAdapter()).execute("listSu01RiskAccounts", Map.of());
    assertEquals(1, rows.size());
    assertEquals("account_locked", rows.getFirst().get("riskReason"));
  }

  @Test void rejectsUnexpectedParametersBeforeCallingSap() {
    var service = new GuardService(new StubAdapter());
    var error = assertThrows(IllegalArgumentException.class,
        () -> service.execute("listSu01Users", Map.of("rfcFunction", "RFC_READ_TABLE")));
    assertEquals("PARAMETER_NOT_ALLOWED", error.getMessage());
  }

  @Test void rejectsInvalidFiltersAndBounds() {
    var service = new GuardService(new StubAdapter());
    assertEquals("MAX_ROWS_INVALID", assertThrows(IllegalArgumentException.class,
        () -> service.execute("listSu01Users", Map.of("maxRows", 0))).getMessage());
    assertEquals("INACTIVE_DAYS_INVALID", assertThrows(IllegalArgumentException.class,
        () -> service.execute("listSu01Users", Map.of("inactiveDays", "many"))).getMessage());
    assertEquals("USER_TYPE_INVALID", assertThrows(IllegalArgumentException.class,
        () -> service.execute("listSu01Users", Map.of("userType", "Administrator"))).getMessage());
    assertEquals("DIMENSION_INVALID", assertThrows(IllegalArgumentException.class,
        () -> service.execute("summarizeSu01Accounts", Map.of("dimension", "email"))).getMessage());
  }

  @Test void requiresAValidUsernameForDetail() {
    var service = new GuardService(new StubAdapter());
    assertEquals("USERNAME_REQUIRED", assertThrows(IllegalArgumentException.class,
        () -> service.execute("getSu01UserDetail", Map.of())).getMessage());
  }

  @Test void validatesAndRoutesOnlyTheGovernedCommunicationUserCreation() {
    var service = new GuardService(new StubAdapter());
    var result = service.execute("createSu01CommunicationUser", Map.of(
        "username", "N8N_DEMO_01", "firstName", "n8n", "lastName", "Demo User",
        "validDays", 1));
    assertEquals(true, result.getFirst().get("created"));
    assertEquals("EMAIL_INVALID", assertThrows(IllegalArgumentException.class,
        () -> service.execute("createSu01CommunicationUser", Map.of(
            "username", "N8N_DEMO_01", "firstName", "n8n", "lastName", "Demo User",
            "email", "not-an-email"))).getMessage());
  }

  @Test void routesTheFixedBusinessBapiAliases() {
    var service = new GuardService(new StubAdapter());
    assertEquals("listCompanyCodes", service.execute("listCompanyCodes", Map.of()).getFirst().get("operation"));
    assertEquals("getCompanyCodeDetail", service.execute(
        "getCompanyCodeDetail", Map.of("companyCode", "1000")).getFirst().get("operation"));
    assertEquals("searchMaterials", service.execute(
        "searchMaterials", Map.of("maxRows", 10, "materialPattern", "TG*")).getFirst().get("operation"));
    assertEquals("getMaterialDetail", service.execute(
        "getMaterialDetail", Map.of("material", "TG11", "plant", "1000")).getFirst().get("operation"));
    assertEquals("getPurchaseOrderDetail", service.execute(
        "getPurchaseOrderDetail", Map.of("purchaseOrder", "4500000001")).getFirst().get("operation"));
    assertEquals("getSalesOrderStatus", service.execute(
        "getSalesOrderStatus", Map.of("salesDocument", "5000000001")).getFirst().get("operation"));
    assertEquals("checkMaterialAvailability", service.execute(
        "checkMaterialAvailability", Map.of("material", "TG11", "plant", "1000",
            "requestedDate", "2026-08-25", "requestedQuantity", 2)).getFirst().get("operation"));
    assertEquals("listIncomingInvoices", service.execute(
        "listIncomingInvoices", Map.of("dateFrom", "2026-08-01", "dateTo", "2026-08-21"))
        .getFirst().get("operation"));
    assertEquals("getIncomingInvoiceDetail", service.execute(
        "getIncomingInvoiceDetail", Map.of("invoiceDocument", "5100000001", "fiscalYear", "2026"))
        .getFirst().get("operation"));
    assertEquals("detectPotentialDuplicateInvoices", service.execute(
        "detectPotentialDuplicateInvoices", Map.of(
            "dateFrom", "2026-08-01", "dateTo", "2026-08-21", "vendor", "100012",
            "reference", "SUP-42", "amount", "120.50", "currency", "EUR"))
        .getFirst().get("operation"));
    assertEquals("getVendorOpenItems", service.execute(
        "getVendorOpenItems", Map.of(
            "companyCode", "1000", "vendor", "100012", "keyDate", "2026-08-21"))
        .getFirst().get("operation"));
    assertEquals("getCustomerOpenItems", service.execute(
        "getCustomerOpenItems", Map.of(
            "companyCode", "1000", "customer", "100012", "keyDate", "2026-08-21"))
        .getFirst().get("operation"));
    assertEquals("summarizeOverdueItems", service.execute(
        "summarizeOverdueItems", Map.of(
            "accountType", "vendor", "account", "100012", "companyCode", "1000",
            "keyDate", "2026-08-21")).getFirst().get("operation"));
    assertEquals("getVendorDetail", service.execute(
        "getVendorDetail", Map.of("vendor", "100012", "companyCode", "1000"))
        .getFirst().get("operation"));
    assertEquals("getCustomerDetail", service.execute(
        "getCustomerDetail", Map.of("customer", "100012", "companyCode", "1000"))
        .getFirst().get("operation"));
  }

  @Test void rejectsInvalidBusinessIdentifiersBeforeCallingSap() {
    var service = new GuardService(new StubAdapter());
    assertEquals("COMPANY_CODE_INVALID", assertThrows(IllegalArgumentException.class,
        () -> service.execute("getCompanyCodeDetail", Map.of("companyCode", "1 OR 1"))).getMessage());
    assertEquals("MATERIAL_PATTERN_INVALID", assertThrows(IllegalArgumentException.class,
        () -> service.execute("searchMaterials", Map.of("materialPattern", "*'; delete"))).getMessage());
    assertEquals("MATERIAL_INVALID", assertThrows(IllegalArgumentException.class,
        () -> service.execute("getMaterialDetail", Map.of("material", "bad value"))).getMessage());
    assertEquals("PURCHASE_ORDER_INVALID", assertThrows(IllegalArgumentException.class,
        () -> service.execute("getPurchaseOrderDetail", Map.of("purchaseOrder", "PO-1"))).getMessage());
    assertEquals("SALES_DOCUMENT_INVALID", assertThrows(IllegalArgumentException.class,
        () -> service.execute("getSalesOrderStatus", Map.of("salesDocument", "../1"))).getMessage());
    assertEquals("REQUESTED_QUANTITY_INVALID", assertThrows(IllegalArgumentException.class,
        () -> service.execute("checkMaterialAvailability", Map.of(
            "material", "TG11", "plant", "1000", "requestedDate", "2026-08-25",
            "requestedQuantity", 0))).getMessage());
    assertEquals("DATE_RANGE_INVALID", assertThrows(IllegalArgumentException.class,
        () -> service.execute("listIncomingInvoices", Map.of(
            "dateFrom", "2026-08-21", "dateTo", "2026-01-01"))).getMessage());
    assertEquals("CURRENCY_INVALID", assertThrows(IllegalArgumentException.class,
        () -> service.execute("detectPotentialDuplicateInvoices", Map.of(
            "dateFrom", "2026-08-01", "dateTo", "2026-08-21", "vendor", "100012",
            "reference", "SUP-42", "amount", "120.50", "currency", "EU"))).getMessage());
    assertEquals("ACCOUNT_TYPE_INVALID", assertThrows(IllegalArgumentException.class,
        () -> service.execute("summarizeOverdueItems", Map.of(
            "accountType", "employee", "account", "100012", "companyCode", "1000",
            "keyDate", "2026-08-21"))).getMessage());
  }

  private static final class StubAdapter implements SapAdapter {
    @Override public void ping() {}
    @Override public Backend backend() { return new Backend("S4D", "100", "sap", "2025"); }
    @Override public List<UserRecord> listUsers(Map<String, Object> ignored) {
      return List.of(user("ACTIVE", "Active", ""), user("LOCKED", "Locked", "account_locked"));
    }
    @Override public List<UserRecord> getUser(String username) { return List.of(user(username, "Active", "")); }
    @Override public List<Map<String, Object>> executeBusinessRead(
        String operation, Map<String, Object> parameters) {
      return List.of(Map.of("operation", operation));
    }
    @Override public Map<String, Object> createCommunicationUser(Map<String, Object> parameters) {
      return Map.of("username", parameters.get("username"), "created", true);
    }
    private UserRecord user(String username, String status, String reason) {
      return new UserRecord(username, "Dialog", "", "", "", "", "", "", "Unlocked", status, reason);
    }
  }
}
