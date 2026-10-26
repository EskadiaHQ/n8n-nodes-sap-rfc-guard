package com.logali.rfcguard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class HttpApiTest {
  private static final String TOKEN = "01234567890123456789012345678901";
  private HttpApi api;
  private int port;

  @BeforeEach void start() throws Exception {
    try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
    var configuration = new Configuration(port, TOKEN, "TEST", 50, 90, 30, "", "", Map.of());
    api = new HttpApi(configuration, new StubAdapter());
    api.start();
  }

  @AfterEach void stop() { api.close(); }

  @Test void protectsHealthAndReturnsBackendIdentity() throws Exception {
    assertEquals(401, send("GET", "/v1/health", null, null).statusCode());
    var response = send("GET", "/v1/health", null, TOKEN);
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("\"systemId\":\"S4D\""));
    assertTrue(response.body().contains("\"readOnly\":true"));
  }

  @Test void distinguishesPlatformManagedAndLocallyRegisteredDestinations() {
    var managed = new Configuration(port, TOKEN, "BTP_DESTINATION", 50, 90, 30,
        "", "", Map.of());
    var local = new Configuration(port, TOKEN, "LOCAL_DESTINATION", 50, 90, 30,
        "", "", Map.of("jco.client.ashost", "sap.internal"));
    assertTrue(managed.usesManagedDestination());
    assertTrue(!local.usesManagedDestination());
  }

  @Test void readsTheApiTokenFromACloudFoundryUserProvidedService() {
    String services = "{\"user-provided\":[{\"name\":\"rfc-guard-secrets-415\","
        + "\"credentials\":{\"rfcGuardApiToken\":\"" + TOKEN + "\"}}]}";
    assertEquals(TOKEN, Configuration.resolveApiToken(Map.of("VCAP_SERVICES", services)));
    assertEquals(TOKEN, Configuration.resolveApiToken(Map.of("RFC_GUARD_API_TOKEN", TOKEN)));
  }

  @Test void blocksTechnicalFunctionNames() throws Exception {
    var response = send("POST", "/v1/operations/BAPI_USER_GETLIST/execute",
        "{\"operation\":\"BAPI_USER_GETLIST\",\"context\":{\"readOnly\":true}}", TOKEN);
    assertEquals(403, response.statusCode());
    assertTrue(response.body().contains("OPERATION_NOT_ALLOWED"));
  }

  @Test void requiresReadOnlyAttestation() throws Exception {
    var response = send("POST", "/v1/operations/listSu01Users/execute",
        "{\"operation\":\"listSu01Users\",\"context\":{\"readOnly\":false}}", TOKEN);
    assertEquals(403, response.statusCode());
    assertTrue(response.body().contains("READ_ONLY_CONTRACT_REQUIRED"));
  }

  @Test void rejectsUnknownParametersAtTheHttpBoundary() throws Exception {
    var response = send("POST", "/v1/operations/listSu01Users/execute",
        "{\"operation\":\"listSu01Users\",\"parameters\":{\"table\":\"USR02\"},\"context\":{\"readOnly\":true}}", TOKEN);
    assertEquals(400, response.statusCode());
    assertTrue(response.body().contains("PARAMETER_NOT_ALLOWED"));
  }

  @Test void returnsGovernedRealSourceMetadata() throws Exception {
    var response = send("POST", "/v1/operations/listSu01Users/execute",
        "{\"operation\":\"listSu01Users\",\"parameters\":{},\"context\":{\"readOnly\":true}}", TOKEN);
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("\"source\":\"sap-jco\""));
    assertTrue(response.body().contains("\"syntheticData\":false"));
  }

  @Test void exposesAndExecutesOnlyTheNewBusinessAliasesInsteadOfBapiNames() throws Exception {
    var health = send("GET", "/v1/health", null, TOKEN);
    assertTrue(health.body().contains("listCompanyCodes"));
    assertTrue(health.body().contains("getPurchaseOrderDetail"));
    assertTrue(!health.body().contains("BAPI_COMPANYCODE_GETLIST"));
    var response = send("POST", "/v1/operations/getCompanyCodeDetail/execute",
        "{\"operation\":\"getCompanyCodeDetail\",\"parameters\":{\"companyCode\":\"1000\"},"
            + "\"context\":{\"readOnly\":true}}", TOKEN);
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("getCompanyCodeDetail"));
  }

  @Test void timesOutAStalledSapRead() throws Exception {
    api.close();
    var configuration = new Configuration(port, TOKEN, "TEST", 50, 90, 1, "", "", Map.of());
    api = new HttpApi(configuration, new SlowAdapter());
    api.start();
    var response = send("POST", "/v1/operations/listSu01Users/execute",
        "{\"operation\":\"listSu01Users\",\"parameters\":{},\"context\":{\"readOnly\":true}}", TOKEN);
    assertEquals(504, response.statusCode());
    assertTrue(response.body().contains("SAP_READ_TIMEOUT"));
  }

  @Test void isolatesUserProvisioningAndRequiresTargetBoundConfirmation() throws Exception {
    api.close();
    var configuration = new Configuration(port, TOKEN, "TEST", 50, 90, 30, "", "",
        "user-provisioning", true, "N8N_DEMO_", "ZN8N_MOODLE", 7,
        "Temporary-Only-123!", Map.of());
    api = new HttpApi(configuration, new StubAdapter());
    api.start();
    String parameters = "{\"username\":\"N8N_DEMO_01\",\"firstName\":\"n8n\","
        + "\"lastName\":\"Demo User\",\"validDays\":1}";
    var denied = send("POST", "/v1/operations/createSu01CommunicationUser/execute",
        "{\"operation\":\"createSu01CommunicationUser\",\"parameters\":" + parameters
            + ",\"context\":{\"readOnly\":false,\"write\":true,"
            + "\"confirmation\":\"CREATE OTHER_USER\"}}", TOKEN);
    assertEquals(403, denied.statusCode());
    var response = send("POST", "/v1/operations/createSu01CommunicationUser/execute",
        "{\"operation\":\"createSu01CommunicationUser\",\"parameters\":" + parameters
            + ",\"context\":{\"readOnly\":false,\"write\":true,"
            + "\"confirmation\":\"CREATE N8N_DEMO_01\"}}", TOKEN);
    assertEquals(200, response.statusCode());
    assertTrue(response.body().contains("\"write\":true"));
    assertTrue(!response.body().toLowerCase().contains("password"));
    assertEquals(403, send("POST", "/v1/operations/listSu01Users/execute",
        "{\"operation\":\"listSu01Users\",\"context\":{\"readOnly\":true}}", TOKEN).statusCode());
  }

  @Test void requiresTheMatchingVerbForEachUserLifecycleChange() throws Exception {
    api.close();
    var configuration = new Configuration(port, TOKEN, "TEST", 50, 90, 30, "", "",
        "user-provisioning", true, "N8N_DEMO_", "ZN8N_MOODLE", 7,
        "Temporary-Only-123!", Map.of());
    api = new HttpApi(configuration, new StubAdapter());
    api.start();
    var cases = Map.of("resetSu01UserPassword", "RESET", "lockSu01User", "LOCK",
        "unlockSu01User", "UNLOCK");
    for (var entry : cases.entrySet()) {
      String path = "/v1/operations/" + entry.getKey() + "/execute";
      String body = "{\"operation\":\"" + entry.getKey() + "\",\"parameters\":{\"username\":\"N8N_DEMO_01\"},"
          + "\"context\":{\"readOnly\":false,\"write\":true,\"confirmation\":\"%s N8N_DEMO_01\"}}";
      assertEquals(403, send("POST", path, body.formatted("CREATE"), TOKEN).statusCode());
      var accepted = send("POST", path, body.formatted(entry.getValue()), TOKEN);
      assertEquals(200, accepted.statusCode(), entry.getKey());
      assertTrue(accepted.body().contains("\"write\":true"));
    }
  }

  @Test void readOnlySidecarRejectsUserLifecycleChanges() throws Exception {
    var response = send("POST", "/v1/operations/resetSu01UserPassword/execute",
        "{\"operation\":\"resetSu01UserPassword\",\"parameters\":{\"username\":\"N8N_DEMO_01\"},"
            + "\"context\":{\"readOnly\":false,\"write\":true,\"confirmation\":\"RESET N8N_DEMO_01\"}}",
        TOKEN);
    assertEquals(403, response.statusCode());
  }

  @Test void buildsOperationSpecificConfirmations() {
    var parameters = Map.<String, Object>of("username", "n8n_demo_01");
    assertEquals("CREATE N8N_DEMO_01", HttpApi.expectedConfirmation("createSu01CommunicationUser", parameters));
    assertEquals("RESET N8N_DEMO_01", HttpApi.expectedConfirmation("resetSu01UserPassword", parameters));
    assertEquals("LOCK N8N_DEMO_01", HttpApi.expectedConfirmation("lockSu01User", parameters));
    assertEquals("UNLOCK N8N_DEMO_01", HttpApi.expectedConfirmation("unlockSu01User", parameters));
    assertEquals("", HttpApi.expectedConfirmation("listSu01Users", parameters));
  }

  @Test void businessModeKeepsReadsAndRequiresInvoiceBoundWriteConfirmation() throws Exception {
    api.close();
    var configuration = new Configuration(port, TOKEN, "TEST", 50, 90, 30, "", "",
        "business-operations", false, "N8N_DEMO_", "ZN8N_MOODLE", 7,
        "Temporary-Only-123!", Map.of());
    api = new HttpApi(configuration, new StubAdapter());
    api.start();
    var health = send("GET", "/v1/health", null, TOKEN);
    assertTrue(health.body().contains("createIncomingInvoice"));
    assertTrue(health.body().contains("getPurchaseOrderDetail"));

    var read = sendWithMode("POST", "/v1/operations/getPurchaseOrderDetail/execute",
        "{\"operation\":\"getPurchaseOrderDetail\",\"parameters\":{\"purchaseOrder\":\"4500000010\"},"
            + "\"context\":{\"readOnly\":true}}", TOKEN, "business-operations");
    assertEquals(200, read.statusCode());

    String parameters = "{\"companyCode\":\"1010\",\"documentDate\":\"2025-02-15\","
        + "\"postingDate\":\"2025-02-15\",\"reference\":\"WB030926-INV01\","
        + "\"currency\":\"EUR\",\"grossAmount\":\"80.00\","
        + "\"purchaseOrder\":\"4500000010\",\"purchaseOrderItem\":\"00010\","
        + "\"itemAmount\":\"80.00\",\"quantity\":\"8\",\"unit\":\"ST\"}";
    var denied = sendWithMode("POST", "/v1/operations/createIncomingInvoice/execute",
        "{\"operation\":\"createIncomingInvoice\",\"parameters\":" + parameters
            + ",\"context\":{\"readOnly\":false,\"write\":true,"
            + "\"confirmation\":\"CREATE INVOICE OTHER\"}}", TOKEN, "business-operations");
    assertEquals(403, denied.statusCode());
    var created = sendWithMode("POST", "/v1/operations/createIncomingInvoice/execute",
        "{\"operation\":\"createIncomingInvoice\",\"parameters\":" + parameters
            + ",\"context\":{\"readOnly\":false,\"write\":true,"
            + "\"confirmation\":\"CREATE INVOICE WB030926-INV01\"}}", TOKEN,
        "business-operations");
    assertEquals(200, created.statusCode());
    assertTrue(created.body().contains("\"write\":true"));
    assertTrue(created.body().contains("5100000001"));

    String receiptParameters = "{\"documentDate\":\"2026-08-25\",\"postingDate\":\"2026-08-25\"," 
        + "\"reference\":\"WB030926-GR01\",\"purchaseOrder\":\"4500000009\"," 
        + "\"purchaseOrderItem\":\"00010\",\"quantity\":\"6\",\"unit\":\"PC\"," 
        + "\"plant\":\"1010\",\"storageLocation\":\"101A\"}";
    var receiptDenied = sendWithMode("POST", "/v1/operations/createGoodsReceipt/execute",
        "{\"operation\":\"createGoodsReceipt\",\"parameters\":" + receiptParameters
            + ",\"context\":{\"readOnly\":false,\"write\":true,"
            + "\"confirmation\":\"POST GOODS RECEIPT OTHER\"}}", TOKEN,
        "business-operations");
    assertEquals(403, receiptDenied.statusCode());
    var receipt = sendWithMode("POST", "/v1/operations/createGoodsReceipt/execute",
        "{\"operation\":\"createGoodsReceipt\",\"parameters\":" + receiptParameters
            + ",\"context\":{\"readOnly\":false,\"write\":true,"
            + "\"confirmation\":\"POST GOODS RECEIPT WB030926-GR01\"}}", TOKEN,
        "business-operations");
    assertEquals(200, receipt.statusCode());
    assertTrue(receipt.body().contains("5000000001"));
  }

  @Test void keepsUserAdministrationAndBusinessWritesOnSeparateSidecarModes() throws Exception {
    api.close();
    api = new HttpApi(new Configuration(port, TOKEN, "TEST", 50, 90, 30, "", "",
        "business-operations", true, "N8N_DEMO_", "ZN8N_MOODLE", 7,
        "Temporary-Only-123!", Map.of()), new StubAdapter());
    api.start();
    var businessHealth = send("GET", "/v1/health", null, TOKEN);
    assertFalse(businessHealth.body().contains("lockSu01User"));
    for (String mode : List.of("user-provisioning", "business-operations")) {
      var lock = sendWithMode("POST", "/v1/operations/lockSu01User/execute",
          "{\"operation\":\"lockSu01User\",\"parameters\":{\"username\":\"N8N_DEMO_01\"},"
              + "\"context\":{\"readOnly\":false,\"write\":true,\"confirmation\":\"LOCK N8N_DEMO_01\"}}",
          TOKEN, mode);
      assertEquals(403, lock.statusCode(), mode);
    }

    api.close();
    api = new HttpApi(new Configuration(port, TOKEN, "TEST", 50, 90, 30, "", "",
        "user-provisioning", true, "N8N_DEMO_", "ZN8N_MOODLE", 7,
        "Temporary-Only-123!", Map.of()), new StubAdapter());
    api.start();
    var provisioningHealth = send("GET", "/v1/health", null, TOKEN);
    assertTrue(provisioningHealth.body().contains("lockSu01User"));
    assertFalse(provisioningHealth.body().contains("createIncomingInvoice"));
    String invoice = "{\"operation\":\"createIncomingInvoice\",\"parameters\":{\"reference\":\"INV01\"},"
        + "\"context\":{\"readOnly\":false,\"write\":true,\"confirmation\":\"CREATE INVOICE INV01\"}}";
    for (String mode : List.of("user-provisioning", "business-operations")) {
      assertEquals(403, sendWithMode("POST", "/v1/operations/createIncomingInvoice/execute",
          invoice, TOKEN, mode).statusCode(), mode);
    }
  }

  private HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
    return sendWithMode(method, path, body, token,
        body != null && body.contains("\"write\":true") ? "user-provisioning" : "read-only");
  }

  private HttpResponse<String> sendWithMode(
      String method, String path, String body, String token, String mode) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    if (token != null) builder.header("Authorization", "Bearer " + token);
    if ("POST".equals(method)) builder.header("Content-Type", "application/json")
        .header("X-RFC-Guard-Mode", mode)
        .POST(HttpRequest.BodyPublishers.ofString(body));
    else builder.GET();
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static class StubAdapter implements SapAdapter {
    @Override public void ping() {}
    @Override public Backend backend() { return new Backend("S4D", "100", "sap.example", "2025"); }
    @Override public List<UserRecord> listUsers(Map<String, Object> ignored) { return List.of(user("TEST_USER")); }
    @Override public List<UserRecord> getUser(String username) { return List.of(user(username)); }
    @Override public List<Map<String, Object>> executeBusinessRead(
        String operation, Map<String, Object> parameters) {
      return List.of(Map.of("operation", operation, "companyCode", parameters.getOrDefault("companyCode", "")));
    }
    @Override public Map<String, Object> createCommunicationUser(Map<String, Object> parameters) {
      return Map.of("username", parameters.get("username"), "created", true,
          "userType", "Communication", "rolesAssigned", 0, "profilesAssigned", 0);
    }
    @Override public Map<String, Object> manageUser(String operation, Map<String, Object> parameters) {
      return Map.of("username", parameters.get("username"), "verified", true, "lockStatus", "Unlocked");
    }
    @Override public Map<String, Object> createIncomingInvoice(Map<String, Object> parameters) {
      return Map.of("invoiceDocument", "5100000001", "fiscalYear", "2025", "created", true,
          "verified", true);
    }
    @Override public Map<String, Object> createGoodsReceipt(Map<String, Object> parameters) {
      return Map.of("materialDocument", "5000000001", "materialDocumentYear", "2026",
          "created", true, "verified", true);
    }
    private UserRecord user(String username) {
      return new UserRecord(username, "Dialog", "Test User", "test@example.com", "2025-01-01",
          "2025-01-01", "9999-12-31", "2026-08-20", "Unlocked", "Active", "");
    }
  }

  private static final class SlowAdapter extends StubAdapter {
    @Override public List<UserRecord> listUsers(Map<String, Object> ignored) {
      try { Thread.sleep(2_500); }
      catch (InterruptedException error) { Thread.currentThread().interrupt(); }
      return super.listUsers(ignored);
    }
  }
}
