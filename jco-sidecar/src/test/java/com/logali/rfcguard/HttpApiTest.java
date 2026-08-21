package com.logali.rfcguard;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

  private HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    if (token != null) builder.header("Authorization", "Bearer " + token);
    if ("POST".equals(method)) builder.header("Content-Type", "application/json")
        .header("X-RFC-Guard-Mode", body != null && body.contains("\"write\":true")
            ? "user-provisioning" : "read-only")
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
