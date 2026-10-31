package com.logali.rfcguard;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.security.KeyStore;

final class HttpApi implements AutoCloseable {
  static final String VERSION = "0.7.0";
  private static final int MAX_BODY_BYTES = 64 * 1024;
  private final Configuration configuration;
  private final SapAdapter adapter;
  private final GuardService service;
  private final ObjectMapper mapper = new ObjectMapper();
  private final HttpServer server;
  private final ExecutorService httpExecutor;
  private final ExecutorService operationExecutor;
  private final FunctionCatalog catalog;
  private final ObjectMapper exactMapper = new ObjectMapper()
      .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
      .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
      .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

  HttpApi(Configuration configuration, SapAdapter adapter) throws IOException {
    this(configuration, adapter, null);
  }

  HttpApi(Configuration configuration, SapAdapter adapter, FunctionCatalog catalog) throws IOException {
    this.configuration = configuration;
    this.catalog = "user-provisioning".equals(configuration.mode()) ? null : catalog;
    this.adapter = adapter;
    this.service = new GuardService(adapter);
    this.server = createServer(configuration);
    this.httpExecutor = Executors.newVirtualThreadPerTaskExecutor();
    this.operationExecutor = Executors.newFixedThreadPool(4,
        Thread.ofPlatform().name("sap-rfc-call-", 0).daemon(true).factory());
    this.server.createContext("/v1/health", this::health);
    this.server.createContext("/v1/operations", this::operation);
    this.server.createContext("/v1/catalog", this::catalog);
    this.server.setExecutor(httpExecutor);
  }

  private static HttpServer createServer(Configuration configuration) throws IOException {
    if (configuration.tlsKeystorePath() == null || configuration.tlsKeystorePath().isBlank()) {
      String bindAddress = System.getenv().getOrDefault("RFC_GUARD_BIND_ADDRESS", "127.0.0.1");
      return HttpServer.create(new InetSocketAddress(bindAddress, configuration.port()), 0);
    }
    try {
      KeyStore keyStore = KeyStore.getInstance("PKCS12");
      char[] password = configuration.tlsKeystorePassword().toCharArray();
      try (var input = new FileInputStream(configuration.tlsKeystorePath())) { keyStore.load(input, password); }
      KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      managers.init(keyStore, password);
      // Let the Java 21 provider negotiate TLS 1.3 or TLS 1.2. This keeps the
      // endpoint secure while remaining compatible with Node.js/OpenSSL clients.
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(managers.getKeyManagers(), null, null);
      HttpsServer server = HttpsServer.create(new InetSocketAddress("0.0.0.0", configuration.port()), 0);
      server.setHttpsConfigurator(new HttpsConfigurator(context));
      return server;
    } catch (Exception error) {
      throw new IOException("Unable to initialize sidecar TLS", error);
    }
  }

  void start() { server.start(); }
  @Override public void close() {
    server.stop(2);
    httpExecutor.shutdownNow();
    operationExecutor.shutdownNow();
    adapter.close();
  }

  private void health(HttpExchange exchange) throws IOException {
    String correlationId = correlationId(exchange);
    if (!authorized(exchange)) { unauthorized(exchange, correlationId); return; }
    if (!"GET".equals(exchange.getRequestMethod())) { error(exchange, 405, "METHOD_NOT_ALLOWED", correlationId); return; }
    try {
      adapter.ping();
      var backend = adapter.backend();
      boolean provisioning = "user-provisioning".equals(configuration.mode());
      boolean businessOperations = "business-operations".equals(configuration.mode());
      var operations = provisioning ? GuardService.USER_WRITE_OPERATIONS
          : businessOperations
              ? union(GuardService.READ_OPERATIONS, GuardService.BUSINESS_WRITE_OPERATIONS)
              : GuardService.READ_OPERATIONS;
      response(exchange, 200, Map.of(
          "status", "ok", "service", provisioning
              ? "sap-rfc-guard-jco-provisioning"
              : businessOperations ? "sap-rfc-guard-jco-business" : "sap-rfc-guard-jco",
          "version", VERSION,
          "backend", backendMap(backend),
          "capabilities", Map.of(
              "readOnly", !provisioning && !businessOperations,
              "writeEnabled", (provisioning && configuration.userCreationEnabled()) || businessOperations,
              "operations", operations,
              "functionCatalog", catalog == null ? Map.of("enabled", false) : catalog.health()),
          "timestamp", Instant.now().toString(), "correlationId", correlationId));
    } catch (RuntimeException error) {
      response(exchange, 503, Map.of("status", "unavailable", "service", "sap-rfc-guard-jco",
          "error", "SAP_CONNECTION_UNAVAILABLE", "correlationId", correlationId));
    }
  }

  @SuppressWarnings("unchecked")
  private void operation(HttpExchange exchange) throws IOException {
    String correlationId = correlationId(exchange);
    if (!authorized(exchange)) { unauthorized(exchange, correlationId); return; }
    String prefix = "/v1/operations/";
    String path = exchange.getRequestURI().getPath();
    if (!"POST".equals(exchange.getRequestMethod()) || !path.startsWith(prefix) || !path.endsWith("/execute")) {
      error(exchange, 403, "OPERATION_NOT_ALLOWED", correlationId); return;
    }
    String operation = path.substring(prefix.length(), path.length() - "/execute".length());
    boolean provisioning = "user-provisioning".equals(configuration.mode());
    boolean businessOperations = "business-operations".equals(configuration.mode());
    boolean readOperation = !provisioning && GuardService.READ_OPERATIONS.contains(operation);
    boolean userWriteOperation = provisioning && configuration.userCreationEnabled()
        && GuardService.USER_WRITE_OPERATIONS.contains(operation);
    boolean businessWriteOperation = businessOperations
        && GuardService.BUSINESS_WRITE_OPERATIONS.contains(operation);
    boolean writeOperation = userWriteOperation || businessWriteOperation;
    if (!readOperation && !writeOperation) { error(exchange, 403, "OPERATION_NOT_ALLOWED", correlationId); return; }
    try {
      byte[] bodyBytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
      if (bodyBytes.length > MAX_BODY_BYTES) { error(exchange, 413, "REQUEST_TOO_LARGE", correlationId); return; }
      Map<String, Object> body = mapper.readValue(bodyBytes, new TypeReference<>() {});
      Map<String, Object> parameters = body.get("parameters") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
      Map<String, Object> context = body.get("context") instanceof Map<?, ?> map
          ? (Map<String, Object>) map : Map.of();
      if (!operation.equals(body.get("operation"))) {
        error(exchange, 403, "OPERATION_CONTRACT_REQUIRED", correlationId); return;
      }
      if (readOperation && (!Boolean.TRUE.equals(context.get("readOnly"))
          || !(businessOperations ? "business-operations" : "read-only")
              .equals(exchange.getRequestHeaders().getFirst("X-RFC-Guard-Mode")))) {
        error(exchange, 403, "READ_ONLY_CONTRACT_REQUIRED", correlationId); return;
      }
      if (writeOperation && (!Boolean.TRUE.equals(context.get("write"))
          || !Boolean.FALSE.equals(context.get("readOnly"))
          || !(userWriteOperation ? "user-provisioning" : "business-operations")
              .equals(exchange.getRequestHeaders().getFirst("X-RFC-Guard-Mode"))
          || !expectedConfirmation(operation, parameters).equals(context.get("confirmation")))) {
        error(exchange, 403, "WRITE_CONFIRMATION_REQUIRED", correlationId); return;
      }
      var data = withinTimeout(() -> service.execute(operation, parameters));
      var backend = adapter.backend();
      var meta = new LinkedHashMap<String, Object>();
      meta.put("source", "sap-jco");
      meta.put("syntheticData", false);
      meta.put("readOnly", readOperation);
      meta.put("write", writeOperation);
      meta.put("operation", operation);
      meta.put("rowCount", data.size());
      meta.put("correlationId", correlationId);
      meta.put("backend", backendMap(backend));
      response(exchange, 200, Map.of("operation", operation, "correlationId", correlationId, "data", data, "meta", meta));
    } catch (IllegalArgumentException error) {
      error(exchange, 400, safeCode(error.getMessage(), "INVALID_REQUEST"), correlationId);
    } catch (TimeoutException error) {
      error(exchange, 504, writeOperation ? "SAP_WRITE_TIMEOUT" : "SAP_READ_TIMEOUT", correlationId);
    } catch (ExecutionException error) {
      Throwable cause = error.getCause();
      if (cause instanceof IllegalArgumentException invalid) {
        error(exchange, 400, safeCode(invalid.getMessage(), "INVALID_REQUEST"), correlationId);
      } else {
        logOperationFailure(operation, correlationId, cause);
        error(exchange, 502, writeOperation ? "SAP_WRITE_FAILED" : "SAP_READ_FAILED", correlationId);
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      error(exchange, 503, "REQUEST_INTERRUPTED", correlationId);
    } catch (Exception error) {
      logOperationFailure(operation, correlationId, error);
      error(exchange, 502, writeOperation ? "SAP_WRITE_FAILED" : "SAP_READ_FAILED", correlationId);
    }
  }

  /**
   * GET  /v1/catalog/functions?query=&cursor=&limit=   approved AND present in SAP
   * GET  /v1/catalog/functions/{name}                  real signature, policy and documentation
   * POST /v1/catalog/functions/{name}/execute          re-validated, read-only execution
   * GET  /v1/catalog/search?pattern=&limit=            administrative candidate search
   */
  @SuppressWarnings("unchecked")
  private void catalog(HttpExchange exchange) throws IOException {
    String correlationId = correlationId(exchange);
    if (!authorized(exchange)) { unauthorized(exchange, correlationId); return; }
    if (catalog == null) {
      catalogError(exchange, new CatalogException(403, "CATALOG_DISABLED",
          "The function catalog is not enabled on this sidecar."), correlationId);
      return;
    }
    String path = exchange.getRequestURI().getRawPath();
    String method = exchange.getRequestMethod();
    String function = null;
    long started = System.nanoTime();
    try {
      var query = queryParameters(exchange.getRequestURI().getRawQuery());
      if ("GET".equals(method) && "/v1/catalog/functions".equals(path)) {
        int cursor = integerParameter(query, "cursor", 0);
        int limit = integerParameter(query, "limit", 20);
        catalogResponse(exchange, withinTimeout(() -> catalog.list(query.get("query"), cursor, limit)), correlationId);
        return;
      }
      if ("GET".equals(method) && "/v1/catalog/search".equals(path)) {
        int limit = integerParameter(query, "limit", 20);
        catalogResponse(exchange, withinTimeout(() -> catalog.search(query.get("pattern"), limit)), correlationId);
        return;
      }
      String prefix = "/v1/catalog/functions/";
      if (!path.startsWith(prefix)) throw new CatalogException(404, "CATALOG_ROUTE_NOT_FOUND", "Unknown catalog route.");
      String rest = path.substring(prefix.length());
      boolean execute = rest.endsWith("/execute");
      String encodedName = execute ? rest.substring(0, rest.length() - "/execute".length()) : rest;
      if (encodedName.contains("/")) throw new CatalogException(400, "FUNCTION_NAME_INVALID", "Encode / in function names as %2F.");
      function = java.net.URLDecoder.decode(encodedName, StandardCharsets.UTF_8);
      String name = function;
      if ("GET".equals(method) && !execute) {
        catalogResponse(exchange, withinTimeout(() -> catalog.signature(name)), correlationId);
        return;
      }
      if (!"POST".equals(method) || !execute) throw new CatalogException(405, "METHOD_NOT_ALLOWED", "Unsupported catalog method.");
      String expectedMode = "business-operations".equals(configuration.mode()) ? "business-operations" : "read-only";
      byte[] bodyBytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
      if (bodyBytes.length > MAX_BODY_BYTES) throw new CatalogException(413, "REQUEST_TOO_LARGE", "The request exceeds 64 KiB.");
      Map<String, Object> body;
      try { body = exactMapper.readValue(bodyBytes, new TypeReference<>() {}); }
      catch (Exception error) { throw new CatalogException(400, "JSON_INVALID", "The request body is not valid JSON."); }
      Map<String, Object> context = body.get("context") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
      if (!name.equalsIgnoreCase(String.valueOf(body.get("function")))
          || !Boolean.TRUE.equals(context.get("readOnly"))
          || !expectedMode.equals(exchange.getRequestHeaders().getFirst("X-RFC-Guard-Mode"))) {
        throw new CatalogException(403, "READ_ONLY_CONTRACT_REQUIRED",
            "The body must name the same function and carry the read-only contract for this sidecar.");
      }
      Map<String, Object> parameters = catalogParameters(body);
      Map<String, Object> options = body.get("options") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
      Integer maxRows = options.get("maxRows") == null ? null : integerValue(options.get("maxRows"), "MAX_ROWS_INVALID");
      boolean dryRun = Boolean.TRUE.equals(options.get("dryRun"));
      var result = withinTimeout(() -> catalog.execute(name, parameters, maxRows, dryRun));
      logCatalogCall(name, correlationId, String.valueOf(result.get("outcome")), started);
      catalogResponse(exchange, result, correlationId);
    } catch (CatalogException error) {
      if (function != null) logCatalogCall(function, correlationId, error.code(), started);
      catalogError(exchange, error, correlationId);
    } catch (TimeoutException error) {
      catalogError(exchange, new CatalogException(504, "SAP_READ_TIMEOUT",
          "SAP did not answer within " + configuration.requestTimeoutSeconds() + " s.", Map.of("category", "TRANSPORT")), correlationId);
    } catch (ExecutionException error) {
      Throwable cause = error.getCause();
      if (cause instanceof CatalogException refused) {
        if (function != null) logCatalogCall(function, correlationId, refused.code(), started);
        catalogError(exchange, refused, correlationId);
      } else {
        logOperationFailure("catalog:" + function, correlationId, cause);
        catalogError(exchange, new CatalogException(502, "SAP_READ_FAILED", "SAP could not complete the catalog request."), correlationId);
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      error(exchange, 503, "REQUEST_INTERRUPTED", correlationId);
    } catch (RuntimeException error) {
      logOperationFailure("catalog:" + function, correlationId, error);
      catalogError(exchange, new CatalogException(500, "CATALOG_INTERNAL_ERROR", "The catalog request failed inside the sidecar."), correlationId);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> catalogParameters(Map<String, Object> body) {
    Object raw = body.get("parametersJson");
    if (raw instanceof String json) {
      try {
        Object parsed = exactMapper.readValue(json.isBlank() ? "{}" : json, Object.class);
        if (parsed instanceof Map<?, ?> map) return (Map<String, Object>) map;
      } catch (Exception error) {
        throw new CatalogException(400, "PARAMETERS_JSON_INVALID", "parametersJson is not valid JSON (duplicated keys are rejected).");
      }
      throw new CatalogException(400, "PARAMETERS_JSON_INVALID", "parametersJson must be a JSON object keyed by SAP parameter name.");
    }
    if (body.get("parameters") instanceof Map<?, ?> map) return (Map<String, Object>) map;
    if (body.get("parameters") == null) return Map.of();
    throw new CatalogException(400, "PARAMETERS_JSON_INVALID", "parameters must be a JSON object keyed by SAP parameter name.");
  }

  private static Map<String, String> queryParameters(String rawQuery) {
    var output = new LinkedHashMap<String, String>();
    if (rawQuery == null || rawQuery.isBlank()) return output;
    for (String pair : rawQuery.split("&")) {
      int index = pair.indexOf('=');
      String key = java.net.URLDecoder.decode(index < 0 ? pair : pair.substring(0, index), StandardCharsets.UTF_8);
      String value = index < 0 ? "" : java.net.URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8);
      output.put(key, value);
    }
    return output;
  }

  private static int integerParameter(Map<String, String> query, String key, int fallback) {
    String raw = query.get(key);
    if (raw == null || raw.isBlank()) return fallback;
    return integerValue(raw, key.toUpperCase() + "_INVALID");
  }

  private static int integerValue(Object raw, String code) {
    try { return new java.math.BigDecimal(raw.toString().trim()).intValueExact(); }
    catch (RuntimeException error) { throw new CatalogException(400, code, "The value must be a whole number."); }
  }

  private void catalogResponse(HttpExchange exchange, Map<String, Object> body, String correlationId) throws IOException {
    var output = new LinkedHashMap<String, Object>(body);
    output.put("correlationId", correlationId);
    if (output.get("meta") instanceof Map<?, ?> meta) {
      var withId = new LinkedHashMap<Object, Object>(meta);
      withId.put("correlationId", correlationId);
      output.put("meta", withId);
    }
    response(exchange, 200, output);
  }

  private void catalogError(HttpExchange exchange, CatalogException error, String correlationId) throws IOException {
    var body = new LinkedHashMap<String, Object>();
    body.put("error", error.code());
    body.put("message", error.getMessage());
    if (!error.details().isEmpty()) body.put("details", error.details());
    body.put("correlationId", correlationId);
    response(exchange, error.status(), body);
  }

  private static void logCatalogCall(String function, String correlationId, String outcome, long started) {
    System.out.printf("RFC_GUARD_CATALOG_CALL function=%s correlationId=%s outcome=%s durationMs=%d%n",
        function.replaceAll("[^A-Z0-9_/]", "?"), correlationId, outcome, (System.nanoTime() - started) / 1_000_000);
  }

  private static void logOperationFailure(String operation, String correlationId, Throwable error) {
    String message = error == null || error.getMessage() == null ? error == null ? "unknown" : error.getClass().getSimpleName()
        : error.getMessage().replaceAll("[\\r\\n\\t]+", " ").replaceAll("(?i)(password|token|authorization|cookie)\\s*[:=]\\s*\\S+", "$1=[REDACTED]");
    System.err.printf("RFC_GUARD_OPERATION_FAILED operation=%s correlationId=%s cause=%s%n",
        operation, correlationId, message.substring(0, Math.min(message.length(), 1200)));
  }

  private boolean authorized(HttpExchange exchange) {
    return ("Bearer " + configuration.apiToken()).equals(
        exchange.getRequestHeaders().getFirst("Authorization"))
        || configuration.apiToken().equals(
            exchange.getRequestHeaders().getFirst("X-RFC-Guard-Token"));
  }

  private <T> T withinTimeout(Callable<T> operation)
      throws ExecutionException, InterruptedException, TimeoutException {
    var task = operationExecutor.submit(operation);
    try {
      return task.get(configuration.requestTimeoutSeconds(), TimeUnit.SECONDS);
    } catch (TimeoutException | InterruptedException error) {
      task.cancel(true);
      throw error;
    }
  }

  private void unauthorized(HttpExchange exchange, String correlationId) throws IOException {
    error(exchange, 401, "UNAUTHORIZED", correlationId);
  }

  private void error(HttpExchange exchange, int status, String code, String correlationId) throws IOException {
    response(exchange, status, Map.of("error", code, "message", message(code), "correlationId", correlationId));
  }

  private void response(HttpExchange exchange, int status, Object body) throws IOException {
    byte[] payload = mapper.writeValueAsBytes(body);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    exchange.sendResponseHeaders(status, payload.length);
    exchange.getResponseBody().write(payload);
    exchange.close();
  }

  private static Map<String, String> backendMap(SapAdapter.Backend backend) {
    return Map.of("systemId", backend.systemId(), "client", backend.client(), "host", backend.host(), "release", backend.release());
  }

  private static String correlationId(HttpExchange exchange) {
    String supplied = exchange.getRequestHeaders().getFirst("X-Correlation-Id");
    return supplied != null && supplied.matches("[A-Za-z0-9_.:@\\-]{1,128}") ? supplied : UUID.randomUUID().toString();
  }

  private static String safeCode(String candidate, String fallback) {
    return candidate != null && candidate.matches("[A-Z][A-Z0-9_]{2,64}") ? candidate : fallback;
  }

  /**
   * Exact, operation-specific confirmation: CREATE, RESET, LOCK or UNLOCK followed by the
   * username; CREATE INVOICE, PARK INVOICE or POST GOODS RECEIPT followed by the reference.
   */
  static String expectedConfirmation(String operation, Map<String, Object> parameters) {
    if (GuardService.BUSINESS_WRITE_OPERATIONS.contains(operation)) {
      Object reference = parameters.get("reference");
      if ("createGoodsReceipt".equals(operation)) {
        return reference == null ? "" : "POST GOODS RECEIPT "
            + reference.toString().trim().toUpperCase();
      }
      String verb = "parkIncomingInvoice".equals(operation) ? "PARK" : "CREATE";
      return reference == null ? "" : verb + " INVOICE " + reference.toString().trim().toUpperCase();
    }
    Object username = parameters.get("username");
    if (username == null) return "";
    String verb = switch (operation) {
      case "createSu01CommunicationUser" -> "CREATE";
      case "resetSu01UserPassword" -> "RESET";
      case "lockSu01User" -> "LOCK";
      case "unlockSu01User" -> "UNLOCK";
      default -> "";
    };
    return verb.isEmpty() ? "" : verb + " " + username.toString().trim().toUpperCase();
  }

  private static Set<String> union(Set<String> first, Set<String> second) {
    var result = new java.util.LinkedHashSet<String>(first);
    result.addAll(second);
    return Set.copyOf(result);
  }

  private static String message(String code) {
    return switch (code) {
      case "UNAUTHORIZED" -> "A valid sidecar credential is required.";
      case "READ_ONLY_CONTRACT_REQUIRED" -> "A matching read-only business operation contract is required.";
      case "WRITE_CONFIRMATION_REQUIRED" -> "A matching explicit confirmation is required for this write operation.";
      case "USER_NOT_FOUND" -> "The governed SAP user does not exist.";
      case "OPERATION_CONTRACT_REQUIRED" -> "The operation body must match the governed route.";
      case "REQUEST_TOO_LARGE" -> "The request exceeds 64 KiB.";
      case "USERNAME_REQUIRED" -> "The username parameter is required.";
      case "USERNAME_INVALID" -> "The username parameter is invalid.";
      case "USERNAME_PREFIX_INVALID" -> "The username does not match the configured provisioning prefix.";
      case "USER_CREATION_DISABLED" -> "User creation is disabled for this sidecar.";
      case "FIRST_NAME_INVALID" -> "firstName must contain 1-40 characters.";
      case "LAST_NAME_INVALID" -> "lastName must contain 1-40 characters.";
      case "EMAIL_INVALID" -> "email must be empty or syntactically valid.";
      case "VALID_DAYS_INVALID" -> "validDays is outside the operated validity limit.";
      case "PARAMETER_NOT_ALLOWED" -> "The request contains a parameter not approved for this operation.";
      case "MAX_ROWS_INVALID" -> "maxRows must be an integer within the operated limit.";
      case "INACTIVE_DAYS_INVALID" -> "inactiveDays must be an integer between 1 and 3650.";
      case "CLIENT_INVALID" -> "client must contain exactly three digits.";
      case "CLIENT_MISMATCH" -> "The requested client does not match the operated SAP destination.";
      case "USER_TYPE_INVALID" -> "userType is not one of the governed SU01 user types.";
      case "ACCOUNT_STATUS_INVALID" -> "accountStatus is not one of the governed account states.";
      case "DIMENSION_INVALID" -> "dimension must be accountStatus or userType.";
      case "COMPANY_CODE_INVALID" -> "companyCode must contain exactly four letters or numbers.";
      case "MATERIAL_PATTERN_INVALID" -> "materialPattern contains unsupported characters or is too long.";
      case "DESCRIPTION_PATTERN_INVALID" -> "descriptionPattern must contain 1-40 characters when supplied.";
      case "MATERIAL_INVALID" -> "material contains unsupported characters or is too long.";
      case "PLANT_INVALID" -> "plant must contain 1-4 letters or numbers.";
      case "VALUATION_AREA_INVALID" -> "valuationArea must contain 1-4 letters or numbers.";
      case "VALUATION_TYPE_INVALID" -> "valuationType contains unsupported characters or is too long.";
      case "STORAGE_LOCATION_INVALID" -> "storageLocation must contain 1-4 letters or numbers.";
      case "REQUESTED_DATE_INVALID" -> "requestedDate must use YYYY-MM-DD.";
      case "REQUESTED_QUANTITY_INVALID" -> "requestedQuantity must be a positive decimal.";
      case "UNIT_INVALID" -> "unit must contain 1-3 letters or numbers.";
      case "CHECK_RULE_INVALID" -> "checkRule must contain 1-2 letters or numbers.";
      case "PURCHASE_ORDER_INVALID" -> "purchaseOrder must contain 1-10 digits.";
      case "SALES_DOCUMENT_INVALID" -> "salesDocument must contain 1-10 digits.";
      case "DATE_RANGE_INVALID" -> "dateFrom and dateTo must use YYYY-MM-DD and span at most 31 days.";
      case "VENDOR_INVALID" -> "vendor must contain 1-10 digits.";
      case "CUSTOMER_INVALID" -> "customer must contain 1-10 digits.";
      case "ACCOUNT_INVALID" -> "account must contain 1-10 digits.";
      case "REFERENCE_INVALID" -> "reference must contain 1-64 characters.";
      case "AMOUNT_INVALID" -> "amount must be a non-negative decimal.";
      case "AMOUNT_TOLERANCE_INVALID" -> "amountTolerance must be a non-negative decimal.";
      case "CURRENCY_INVALID" -> "currency must contain exactly three letters.";
      case "INVOICE_DOCUMENT_INVALID" -> "invoiceDocument must contain 1-10 digits.";
      case "FISCAL_YEAR_INVALID" -> "fiscalYear must contain exactly four digits.";
      case "KEY_DATE_INVALID" -> "keyDate must use YYYY-MM-DD.";
      case "NOTED_ITEMS_INVALID" -> "notedItems must be true or false.";
      case "ACCOUNT_TYPE_INVALID" -> "accountType must be vendor or customer.";
      case "SAP_READ_TIMEOUT" -> "SAP did not complete the governed read within the configured limit.";
      case "REQUEST_INTERRUPTED" -> "The governed read was interrupted before completion.";
      case "SAP_READ_FAILED" -> "SAP could not complete the governed read.";
      case "SAP_WRITE_TIMEOUT" -> "SAP did not complete the governed user change within the configured limit.";
      case "SAP_WRITE_FAILED" -> "SAP could not complete the governed user change.";
      default -> "The requested operation is not permitted.";
    };
  }
}
