package com.logali.rfcguard;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Cloud Foundry/Tomcat entry point required by the SAP Java buildpack JCo runtime. */
public final class BtpRfcGuardServlet extends HttpServlet {
  private static final int MAX_BODY_BYTES = 64 * 1024;
  private final ObjectMapper mapper = new ObjectMapper();
  private Configuration configuration;
  private JcoSapAdapter adapter;
  private GuardService service;
  private ExecutorService operationExecutor;

  @Override public void init() throws ServletException {
    try {
      configuration = Configuration.fromEnvironment();
      if (!configuration.usesManagedDestination()) {
        throw new IllegalArgumentException(
            "SAP_USE_MANAGED_DESTINATION=true is required for the BTP servlet runtime");
      }
      adapter = new JcoSapAdapter(configuration);
      service = new GuardService(adapter);
      operationExecutor = Executors.newFixedThreadPool(4,
          Thread.ofPlatform().name("sap-rfc-btp-call-", 0).daemon(true).factory());
    } catch (RuntimeException error) {
      throw new ServletException("Unable to initialize the governed RFC Guard runtime", error);
    }
  }

  @Override public void destroy() {
    if (operationExecutor != null) operationExecutor.shutdownNow();
    if (adapter != null) adapter.close();
  }

  @Override protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String correlationId = correlationId(request);
    if (!authorized(request)) { error(response, 401, "UNAUTHORIZED", correlationId); return; }
    if (!"/v1/health".equals(request.getRequestURI())) {
      error(response, 404, "OPERATION_NOT_ALLOWED", correlationId); return;
    }
    try {
      adapter.ping();
      var backend = adapter.backend();
      boolean businessOperations = "business-operations".equals(configuration.mode());
      var operations = businessOperations
          ? union(GuardService.READ_OPERATIONS, GuardService.BUSINESS_WRITE_OPERATIONS)
          : GuardService.READ_OPERATIONS;
      response(response, 200, Map.of(
          "status", "ok", "service", businessOperations
              ? "sap-rfc-guard-jco-business" : "sap-rfc-guard-jco", "version", "0.6.0",
          "backend", backendMap(backend),
          "capabilities", Map.of("readOnly", !businessOperations,
              "writeEnabled", businessOperations, "operations", operations),
          "timestamp", Instant.now().toString(), "correlationId", correlationId));
    } catch (RuntimeException failure) {
      response(response, 503, Map.of("status", "unavailable", "service", "sap-rfc-guard-jco",
          "error", "SAP_CONNECTION_UNAVAILABLE", "correlationId", correlationId));
    }
  }

  @Override protected void doPost(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    String correlationId = correlationId(request);
    if (!authorized(request)) { error(response, 401, "UNAUTHORIZED", correlationId); return; }
    String prefix = "/v1/operations/";
    String path = request.getRequestURI();
    if (!path.startsWith(prefix) || !path.endsWith("/execute")) {
      error(response, 403, "OPERATION_NOT_ALLOWED", correlationId); return;
    }
    String operation = path.substring(prefix.length(), path.length() - "/execute".length());
    boolean businessOperations = "business-operations".equals(configuration.mode());
    boolean readOperation = GuardService.READ_OPERATIONS.contains(operation);
    boolean writeOperation = businessOperations
        && GuardService.BUSINESS_WRITE_OPERATIONS.contains(operation);
    if (!readOperation && !writeOperation) {
      error(response, 403, "OPERATION_NOT_ALLOWED", correlationId); return;
    }
    try {
      byte[] bodyBytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
      if (bodyBytes.length > MAX_BODY_BYTES) {
        error(response, 413, "REQUEST_TOO_LARGE", correlationId); return;
      }
      Map<String, Object> body = mapper.readValue(bodyBytes, new TypeReference<>() {});
      @SuppressWarnings("unchecked")
      Map<String, Object> parameters = body.get("parameters") instanceof Map<?, ?> map
          ? (Map<String, Object>) map : Map.of();
      @SuppressWarnings("unchecked")
      Map<String, Object> context = body.get("context") instanceof Map<?, ?> map
          ? (Map<String, Object>) map : Map.of();
      if (!operation.equals(body.get("operation"))) {
        error(response, 403, "OPERATION_CONTRACT_REQUIRED", correlationId); return;
      }
      if (readOperation && (!Boolean.TRUE.equals(context.get("readOnly"))
          || !(businessOperations ? "business-operations" : "read-only")
              .equals(request.getHeader("X-RFC-Guard-Mode")))) {
        error(response, 403, "READ_ONLY_CONTRACT_REQUIRED", correlationId); return;
      }
      if (writeOperation && (!Boolean.FALSE.equals(context.get("readOnly"))
          || !Boolean.TRUE.equals(context.get("write"))
          || !"business-operations".equals(request.getHeader("X-RFC-Guard-Mode"))
          || !expectedWriteConfirmation(operation, parameters).equals(context.get("confirmation")))) {
        error(response, 403, "WRITE_CONFIRMATION_REQUIRED", correlationId); return;
      }
      var task = operationExecutor.submit(() -> service.execute(operation, parameters));
      var data = task.get(configuration.requestTimeoutSeconds(), TimeUnit.SECONDS);
      var meta = new LinkedHashMap<String, Object>();
      meta.put("source", "sap-jco");
      meta.put("syntheticData", false);
      meta.put("readOnly", !writeOperation);
      meta.put("write", writeOperation);
      meta.put("operation", operation);
      meta.put("rowCount", data.size());
      meta.put("correlationId", correlationId);
      meta.put("backend", backendMap(adapter.backend()));
      response(response, 200, Map.of("operation", operation, "correlationId", correlationId,
          "data", data, "meta", meta));
    } catch (IllegalArgumentException invalid) {
      error(response, 400, safeCode(invalid.getMessage(), "INVALID_REQUEST"), correlationId);
    } catch (TimeoutException timeout) {
      error(response, 504, writeOperation ? "SAP_WRITE_TIMEOUT" : "SAP_READ_TIMEOUT", correlationId);
    } catch (ExecutionException execution) {
      Throwable cause = execution.getCause();
      if (cause instanceof IllegalArgumentException invalid) {
        error(response, 400, safeCode(invalid.getMessage(), "INVALID_REQUEST"), correlationId);
      } else {
        logFailure(operation, correlationId, cause);
        error(response, 502, writeOperation ? "SAP_WRITE_FAILED" : "SAP_READ_FAILED", correlationId);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      error(response, 503, "REQUEST_INTERRUPTED", correlationId);
    } catch (Exception failure) {
      logFailure(operation, correlationId, failure);
      error(response, 502, writeOperation ? "SAP_WRITE_FAILED" : "SAP_READ_FAILED", correlationId);
    }
  }

  private boolean authorized(HttpServletRequest request) {
    return ("Bearer " + configuration.apiToken()).equals(request.getHeader("Authorization"))
        || configuration.apiToken().equals(request.getHeader("X-RFC-Guard-Token"));
  }

  private void error(HttpServletResponse response, int status, String code, String correlationId)
      throws IOException {
    response(response, status, Map.of("error", code, "message", code,
        "correlationId", correlationId));
  }

  private void response(HttpServletResponse response, int status, Object body) throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("X-Content-Type-Options", "nosniff");
    mapper.writeValue(response.getOutputStream(), body);
  }

  private static Map<String, String> backendMap(SapAdapter.Backend backend) {
    return Map.of("systemId", backend.systemId(), "client", backend.client(),
        "host", backend.host(), "release", backend.release());
  }

  private static String correlationId(HttpServletRequest request) {
    String supplied = request.getHeader("X-Correlation-Id");
    return supplied != null && supplied.matches("[A-Za-z0-9_.:@\\-]{1,128}")
        ? supplied : UUID.randomUUID().toString();
  }

  private static String safeCode(String candidate, String fallback) {
    return candidate != null && candidate.matches("[A-Z][A-Z0-9_]{2,64}") ? candidate : fallback;
  }

  private static void logFailure(String operation, String correlationId, Throwable failure) {
    String message = failure == null || failure.getMessage() == null
        ? "" : failure.getMessage().replaceAll("[\\r\\n\\t]+", " ").replaceAll("(?i)(password|token|secret)=[^ ]+", "$1=[REDACTED]");
    if (message.length() > 240) message = message.substring(0, 240);
    String type = failure == null ? "Unknown" : failure.getClass().getSimpleName();
    System.err.printf("RFC Guard failure operation=%s correlationId=%s type=%s message=%s%n",
        operation, correlationId, type, message);
  }

  private static String expectedWriteConfirmation(
      String operation, Map<String, Object> parameters) {
    Object reference = parameters.get("reference");
    if ("createGoodsReceipt".equals(operation)) {
      return reference == null ? "" : "POST GOODS RECEIPT "
          + reference.toString().trim().toUpperCase();
    }
    String verb = "parkIncomingInvoice".equals(operation) ? "PARK" : "CREATE";
    return reference == null ? "" : verb + " INVOICE " + reference.toString().trim().toUpperCase();
  }

  private static Set<String> union(Set<String> first, Set<String> second) {
    var result = new java.util.LinkedHashSet<String>(first);
    result.addAll(second);
    return Set.copyOf(result);
  }
}
