package com.logali.rfcguard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit coverage of policy and validation rules. SAP integration is proven separately on A4H. */
final class FunctionCatalogTest {
  private static final String TOKEN = "01234567890123456789012345678901";
  private static final String POLICY = """
      {"version":1,"metadataSearch":false,"functions":{
        "BAPI_PO_GETDETAIL1":{"title":"Purchase order","kind":"BAPI",
          "bor":{"objectType":"BUS2012","method":"GETDETAIL1"},"effect":"read","transaction":"none",
          "allowMetadata":true,"allowExecute":true,
          "inputs":{"PURCHASEORDER":{"required":true,"pattern":"[0-9]{10}"},"ITEM_TEXT":{"allowedValues":["","X"]}},
          "outputs":["POHEADER","POITEM"],"maxRows":2,
          "approval":{"approvedBy":"SAP owner","approvedOn":"2026-10-31","reviewReference":"TEST-1"}},
        "BAPI_VENDOR_GETDETAIL":{"kind":"BAPI","bor":{"objectType":"LFA1","method":"GETDETAIL"},
          "effect":"read","transaction":"none","allowMetadata":false,"allowExecute":true,
          "inputs":{"VENDORNO":{"required":true,"pad":"alpha"},"COMPANYCODE":{}},
          "fixed":{"BYPASS_AUTH_CHECK":""},"outputs":["GENERALDETAIL"],
          "approval":{"approvedBy":"SAP owner","approvedOn":"2026-10-31","reviewReference":"TEST-2"}},
        "Z_LISTED_ONLY":{"kind":"RFC","effect":"read","transaction":"none","allowMetadata":true,"allowExecute":false},
        "Z_DECIMAL_CHECK":{"kind":"RFC","effect":"read","transaction":"rollback-after-call","allowExecute":true,
          "inputs":{"AMOUNT":{}},"outputs":["X"],
          "approval":{"approvedBy":"SAP owner","approvedOn":"2026-10-31","reviewReference":"TEST-3"}}
      }}""";
  @TempDir Path directory;
  private Stub stub;
  private FunctionCatalog catalog;

  @BeforeEach void setUp() throws Exception {
    Path policy = directory.resolve("catalog.json");
    Files.writeString(policy, POLICY);
    stub = new Stub();
    catalog = new FunctionCatalog(new CatalogPolicySource(policy), stub, 50,
        new FunctionCatalog.Settings(900, 64, 1_048_576));
  }

  @Test void rejectsWritesForbiddenFunctionsAndUnreviewedExecution() {
    assertTrue(policyError("{\"version\":1,\"functions\":{\"BAPI_X\":{\"kind\":\"RFC\",\"effect\":\"write\",\"transaction\":\"none\"}}}")
        .contains("reads only"));
    assertTrue(policyError("{\"version\":1,\"functions\":{\"RFC_READ_TABLE\":{\"kind\":\"RFC\",\"effect\":\"read\",\"transaction\":\"none\"}}}")
        .contains("generic executor"));
    assertTrue(policyError("{\"version\":1,\"functions\":{\"BAPI_X\":{\"kind\":\"RFC\",\"effect\":\"read\"}}}")
        .contains("transaction"));
    assertTrue(policyError("{\"version\":1,\"functions\":{\"BAPI_X\":{\"kind\":\"RFC\",\"effect\":\"read\",\"transaction\":\"none\",\"allowExecute\":true,\"outputs\":[\"A\"]}}}")
        .contains("approval"));
    assertTrue(policyError("{\"version\":1,\"functions\":{\"BAPI_X\":{\"kind\":\"BAPI\",\"effect\":\"read\",\"transaction\":\"none\"}}}")
        .contains("bor"));
  }

  @Test void listsOnlyApprovedFunctionsThatSapReallyExposes() {
    stub.missing.add("Z_LISTED_ONLY");
    var result = catalog.list("", 0, 10);
    var names = ((List<?>) result.get("functions")).stream().map(row -> ((Map<?, ?>) row).get("name")).toList();
    assertEquals(List.of("BAPI_PO_GETDETAIL1", "BAPI_VENDOR_GETDETAIL", "Z_DECIMAL_CHECK"), names);
    assertTrue(result.get("excluded").toString().contains("NOT_FOUND_IN_SAP"));
    var page = catalog.list("", 0, 1);
    assertEquals(1, ((Map<?, ?>) page.get("page")).get("nextCursor"));
  }

  @Test void separatesMetadataFromExecutionPermission() {
    assertEquals("METADATA_NOT_APPROVED", code(() -> catalog.signature("BAPI_VENDOR_GETDETAIL")));
    assertEquals("EXECUTION_NOT_APPROVED", code(() -> catalog.execute("Z_LISTED_ONLY", Map.of(), null, false)));
    assertEquals("FUNCTION_NOT_APPROVED", code(() -> catalog.execute("BAPI_SALESORDER_GETLIST", Map.of(), null, false)));
    assertEquals("FUNCTION_FORBIDDEN", code(() -> catalog.execute("RFC_READ_TABLE", Map.of(), null, false)));
  }

  @Test void validatesAgainstTheRealSignatureWithReadableErrors() {
    assertEquals("PARAMETER_REQUIRED", code(() -> catalog.execute("BAPI_PO_GETDETAIL1", Map.of(), null, false)));
    var unknown = assertThrows(CatalogException.class,
        () -> catalog.execute("BAPI_PO_GETDETAIL1", Map.of("PURCHASE_ORDER", "4500000008"), null, false));
    assertEquals("PARAMETER_UNKNOWN", unknown.code());
    assertTrue(unknown.getMessage().contains("Did you mean PURCHASEORDER"));
    assertEquals("TYPE_MISMATCH", code(() -> catalog.execute("BAPI_PO_GETDETAIL1",
        Map.of("PURCHASEORDER", new java.math.BigInteger("4500000008")), null, false)));
    assertEquals("VALUE_NOT_ALLOWED", code(() -> catalog.execute("BAPI_PO_GETDETAIL1",
        Map.of("PURCHASEORDER", "4500000008", "ITEM_TEXT", "Y"), null, false)));
    assertEquals("PARAMETER_NOT_ALLOWED", code(() -> catalog.execute("BAPI_PO_GETDETAIL1",
        Map.of("PURCHASEORDER", "4500000008", "VERSION", "X"), null, false)));
    assertEquals("PARAMETER_FIXED_BY_POLICY", code(() -> catalog.execute("BAPI_VENDOR_GETDETAIL",
        Map.of("VENDORNO", "10300001", "BYPASS_AUTH_CHECK", "X"), null, false)));
    assertEquals("MAX_ROWS_INVALID", code(() -> catalog.execute("BAPI_PO_GETDETAIL1",
        Map.of("PURCHASEORDER", "4500000008"), 0, false)));
    var clamped = catalog.execute("BAPI_PO_GETDETAIL1", Map.of("PURCHASEORDER", "4500000008"), 30, true);
    assertEquals(2, ((Map<?, ?>) clamped.get("meta")).get("rowLimit"));
  }

  @Test void keepsLeadingZerosAndFixedValuesInTheValidatedCall() {
    var dry = catalog.execute("BAPI_VENDOR_GETDETAIL", Map.of("VENDORNO", "10300001"), null, true);
    assertEquals("VALIDATED", dry.get("outcome"));
    assertEquals("0010300001", ((Map<?, ?>) dry.get("validatedParameters")).get("VENDORNO"));
    assertEquals("", ((Map<?, ?>) dry.get("validatedParameters")).get("BYPASS_AUTH_CHECK"));
    assertTrue(stub.calls.isEmpty());
  }

  @Test void projectsOutputsSeparatesBusinessMessagesAndBoundsRows() {
    stub.messageType = "E";
    var result = catalog.execute("BAPI_PO_GETDETAIL1", Map.of("PURCHASEORDER", "4500000008"), null, false);
    assertEquals("BUSINESS_ERROR", result.get("outcome"));
    assertTrue(result.get("messages").toString().contains("Purchase order does not exist"));
    var exports = (Map<?, ?>) result.get("exports");
    assertTrue(exports.containsKey("POHEADER"));
    assertFalse(exports.containsKey("POEXPIMPHEADER"));
    var meta = (Map<?, ?>) result.get("meta");
    assertEquals(false, meta.get("syntheticData"));
    assertEquals(Map.of("POITEM", 3), meta.get("truncated"));
    assertEquals(List.of("POHEADER", "POITEM", "RETURN"), stub.calls.get(0).outputs());
    assertEquals(2, stub.calls.get(0).maxRows());
  }

  @Test void refusesABapiWhoseBorRegistrationContradictsThePolicy() {
    stub.bor = Map.of("BAPI_PO_GETDETAIL1", List.of(new CatalogBackend.BorMethod("BUS9999", "OTHER", "Other", "", true)));
    assertEquals("POLICY_KIND_MISMATCH", code(() -> catalog.execute("BAPI_PO_GETDETAIL1",
        Map.of("PURCHASEORDER", "4500000008"), null, false)));
  }

  @Test void validatesDecimalsWithoutRoundingAndKeepsPrecision() {
    assertEquals("DECIMAL_SCALE_EXCEEDED", code(() -> catalog.execute("Z_DECIMAL_CHECK",
        Map.of("AMOUNT", "10.005"), null, true)));
    assertEquals("TYPE_MISMATCH", code(() -> catalog.execute("Z_DECIMAL_CHECK",
        Map.of("AMOUNT", "10,50"), null, true)));
    assertEquals("DECIMAL_TOO_LARGE", code(() -> catalog.execute("Z_DECIMAL_CHECK",
        Map.of("AMOUNT", "123456789012.00"), null, true)));
    var exact = catalog.execute("Z_DECIMAL_CHECK", Map.of("AMOUNT", new BigDecimal("12345678901.10")), null, true);
    assertEquals("12345678901.10", ((Map<?, ?>) exact.get("validatedParameters")).get("AMOUNT"));
    catalog.execute("Z_DECIMAL_CHECK", Map.of("AMOUNT", "1.50"), null, false);
    assertTrue(stub.calls.get(0).rollbackAfterCall());
  }

  @Test void httpRouteRequiresTheReadOnlyContractAndKeepsServerSideChecks() throws Exception {
    int port;
    try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
    var configuration = new Configuration(port, TOKEN, "TEST", 50, 90, 30, "", "", Map.of());
    var api = new HttpApi(configuration, new HttpApiTestAdapter(), catalog);
    api.start();
    try {
      var bypass = post(port, "/v1/catalog/functions/RFC_READ_TABLE/execute",
          "{\"function\":\"RFC_READ_TABLE\",\"context\":{\"readOnly\":true}}", "read-only");
      assertEquals(403, bypass.statusCode());
      assertTrue(bypass.body().contains("FUNCTION_FORBIDDEN"));
      var noContract = post(port, "/v1/catalog/functions/BAPI_PO_GETDETAIL1/execute",
          "{\"function\":\"BAPI_PO_GETDETAIL1\",\"parametersJson\":\"{}\"}", "read-only");
      assertTrue(noContract.body().contains("READ_ONLY_CONTRACT_REQUIRED"));
      var duplicate = post(port, "/v1/catalog/functions/BAPI_PO_GETDETAIL1/execute",
          "{\"function\":\"BAPI_PO_GETDETAIL1\",\"context\":{\"readOnly\":true},"
              + "\"parametersJson\":\"{\\\"PURCHASEORDER\\\":\\\"1\\\",\\\"PURCHASEORDER\\\":\\\"2\\\"}\"}", "read-only");
      assertTrue(duplicate.body().contains("PARAMETERS_JSON_INVALID"));
      var ok = post(port, "/v1/catalog/functions/BAPI_PO_GETDETAIL1/execute",
          "{\"function\":\"BAPI_PO_GETDETAIL1\",\"context\":{\"readOnly\":true},"
              + "\"parametersJson\":\"{\\\"PURCHASEORDER\\\":\\\"4500000008\\\"}\"}", "read-only");
      assertEquals(200, ok.statusCode());
      assertTrue(ok.body().contains("\"correlationId\""));
      var health = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/health"))
          .header("Authorization", "Bearer " + TOKEN).GET().build(), HttpResponse.BodyHandlers.ofString());
      assertTrue(health.body().contains("\"functionCatalog\""));
    } finally {
      api.close();
    }
  }

  private static HttpResponse<String> post(int port, String path, String body, String mode) throws Exception {
    return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
        .header("Authorization", "Bearer " + TOKEN).header("X-RFC-Guard-Mode", mode)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private String policyError(String json) {
    var error = assertThrows(IllegalArgumentException.class, () -> CatalogPolicy.parse(json.getBytes(StandardCharsets.UTF_8)));
    return error.getMessage();
  }

  private static String code(Runnable action) {
    return assertThrows(CatalogException.class, action::run).code();
  }

  private static final class HttpApiTestAdapter implements SapAdapter {
    @Override public void ping() {}
    @Override public Backend backend() { return new Backend("S4D", "100", "sap.example", "2025"); }
    @Override public List<UserRecord> listUsers(Map<String, Object> ignored) { return List.of(); }
    @Override public List<UserRecord> getUser(String username) { return List.of(); }
  }

  private static final class Stub implements CatalogBackend {
    final List<String> missing = new ArrayList<>();
    final List<CallRequest> calls = new ArrayList<>();
    Map<String, List<BorMethod>> bor = Map.of(
        "BAPI_PO_GETDETAIL1", List.of(new BorMethod("BUS2012", "GETDETAIL1", "PurchaseOrder", "", false)),
        "BAPI_VENDOR_GETDETAIL", List.of(new BorMethod("LFA1", "GETDETAIL", "Vendor", "", true)));
    String messageType = "";

    @Override public SapAdapter.Backend backend() { return new SapAdapter.Backend("S4D", "100", "sap.example", "2025"); }

    @Override public Signature signature(String function) {
      if (missing.contains(function)) return null;
      var parameters = new ArrayList<Parameter>();
      switch (function) {
        case "BAPI_PO_GETDETAIL1" -> {
          parameters.add(new Parameter("PURCHASEORDER", "IMPORT", "CHAR", 10, 0, false, "", "PO", "", List.of()));
          parameters.add(new Parameter("ITEM_TEXT", "IMPORT", "CHAR", 1, 0, true, "", "", "", List.of()));
          parameters.add(new Parameter("VERSION", "IMPORT", "CHAR", 1, 0, true, "", "", "", List.of()));
          parameters.add(new Parameter("POHEADER", "EXPORT", "STRUCTURE", 674, 0, true, "", "", "BAPIMEPOHEADER",
              List.of(new Field("PO_NUMBER", "CHAR", 10, 0, "", "", List.of()))));
          parameters.add(new Parameter("POEXPIMPHEADER", "EXPORT", "STRUCTURE", 7, 0, true, "", "", "BAPIEIKP", List.of()));
          parameters.add(new Parameter("POITEM", "TABLES", "TABLE", 1911, 0, true, "", "", "BAPIMEPOITEM", List.of()));
          parameters.add(new Parameter("RETURN", "TABLES", "TABLE", 548, 0, true, "", "", "BAPIRET2", List.of()));
        }
        case "BAPI_VENDOR_GETDETAIL" -> {
          parameters.add(new Parameter("VENDORNO", "IMPORT", "CHAR", 10, 0, false, "", "", "", List.of()));
          parameters.add(new Parameter("COMPANYCODE", "IMPORT", "CHAR", 4, 0, true, "", "", "", List.of()));
          parameters.add(new Parameter("BYPASS_AUTH_CHECK", "IMPORT", "CHAR", 1, 0, true, "", "", "", List.of()));
          parameters.add(new Parameter("GENERALDETAIL", "EXPORT", "STRUCTURE", 379, 0, true, "", "", "", List.of()));
          parameters.add(new Parameter("RETURN", "EXPORT", "STRUCTURE", 470, 0, true, "", "", "BAPIRET1", List.of()));
        }
        case "Z_DECIMAL_CHECK" -> {
          parameters.add(new Parameter("AMOUNT", "IMPORT", "BCD", 7, 2, true, "", "", "", List.of()));
          parameters.add(new Parameter("X", "EXPORT", "CHAR", 1, 0, true, "", "", "", List.of()));
        }
        default -> parameters.add(new Parameter("X", "IMPORT", "CHAR", 1, 0, true, "", "", "", List.of()));
      }
      return new Signature(function, parameters, List.of());
    }

    @Override public RemoteInfo remoteInfo(String function) {
      return missing.contains(function) ? null : new RemoteInfo(true, false);
    }

    @Override public List<SearchHit> search(String pattern, int maxResults) { return List.of(); }
    @Override public Map<String, List<BorMethod>> borMethods() { return bor; }
    @Override public List<String> bapiDocumentation(String objectType, String method, int maxLines) { return List.of("Doc"); }

    @Override public CallResult call(CallRequest request) {
      calls.add(request);
      var item = Map.<String, Object>of("PO_ITEM", "00010", "NET_PRICE", new BigDecimal("10.000000000").toPlainString());
      return new CallResult(
          Map.of("POHEADER", Map.of("PO_NUMBER", "4500000008"), "POEXPIMPHEADER", Map.of()),
          Map.of(),
          Map.of("POITEM", List.of(item, item), "RETURN", messageType.isEmpty() ? List.of()
              : List.of(Map.of("TYPE", messageType, "ID", "06", "NUMBER", "019", "MESSAGE", "Purchase order does not exist"))),
          Map.of("POITEM", 3), "none");
    }
  }
}
