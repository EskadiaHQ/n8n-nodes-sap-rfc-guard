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
  private static final int MAX_BODY_BYTES = 64 * 1024;
  private final Configuration configuration;
  private final SapAdapter adapter;
  private final GuardService service;
  private final ObjectMapper mapper = new ObjectMapper();
  private final HttpServer server;
  private final ExecutorService httpExecutor;
  private final ExecutorService operationExecutor;

  HttpApi(Configuration configuration, SapAdapter adapter) throws IOException {
    this.configuration = configuration;
    this.adapter = adapter;
    this.service = new GuardService(adapter);
    this.server = createServer(configuration);
    this.httpExecutor = Executors.newVirtualThreadPerTaskExecutor();
    this.operationExecutor = Executors.newFixedThreadPool(4,
        Thread.ofPlatform().name("sap-rfc-call-", 0).daemon(true).factory());
    this.server.createContext("/v1/health", this::health);
    this.server.createContext("/v1/operations", this::operation);
    this.server.setExecutor(httpExecutor);
  }

  private static HttpServer createServer(Configuration configuration) throws IOException {
    if (configuration.tlsKeystorePath() == null || configuration.tlsKeystorePath().isBlank()) {
      return HttpServer.create(new InetSocketAddress("127.0.0.1", configuration.port()), 0);
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
      response(exchange, 200, Map.of(
          "status", "ok", "service", "sap-rfc-guard-jco", "version", "0.1.2",
          "backend", backendMap(backend),
          "capabilities", Map.of("readOnly", true, "operations", GuardService.OPERATIONS),
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
    if (!GuardService.OPERATIONS.contains(operation)) { error(exchange, 403, "OPERATION_NOT_ALLOWED", correlationId); return; }
    try {
      byte[] bodyBytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
      if (bodyBytes.length > MAX_BODY_BYTES) { error(exchange, 413, "REQUEST_TOO_LARGE", correlationId); return; }
      Map<String, Object> body = mapper.readValue(bodyBytes, new TypeReference<>() {});
      if (!operation.equals(body.get("operation")) || !Boolean.TRUE.equals(((Map<String, Object>) body.getOrDefault("context", Map.of())).get("readOnly"))) {
        error(exchange, 403, "READ_ONLY_CONTRACT_REQUIRED", correlationId); return;
      }
      Map<String, Object> parameters = body.get("parameters") instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
      var data = withinTimeout(() -> service.execute(operation, parameters));
      var backend = adapter.backend();
      var meta = new LinkedHashMap<String, Object>();
      meta.put("source", "sap-jco");
      meta.put("syntheticData", false);
      meta.put("readOnly", true);
      meta.put("operation", operation);
      meta.put("rowCount", data.size());
      meta.put("correlationId", correlationId);
      meta.put("backend", backendMap(backend));
      response(exchange, 200, Map.of("operation", operation, "correlationId", correlationId, "data", data, "meta", meta));
    } catch (IllegalArgumentException error) {
      error(exchange, 400, safeCode(error.getMessage(), "INVALID_REQUEST"), correlationId);
    } catch (TimeoutException error) {
      error(exchange, 504, "SAP_READ_TIMEOUT", correlationId);
    } catch (ExecutionException error) {
      Throwable cause = error.getCause();
      if (cause instanceof IllegalArgumentException invalid) {
        error(exchange, 400, safeCode(invalid.getMessage(), "INVALID_REQUEST"), correlationId);
      } else {
        error(exchange, 502, "SAP_READ_FAILED", correlationId);
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      error(exchange, 503, "REQUEST_INTERRUPTED", correlationId);
    } catch (Exception error) {
      error(exchange, 502, "SAP_READ_FAILED", correlationId);
    }
  }

  private boolean authorized(HttpExchange exchange) {
    return ("Bearer " + configuration.apiToken()).equals(exchange.getRequestHeaders().getFirst("Authorization"));
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

  private static String message(String code) {
    return switch (code) {
      case "UNAUTHORIZED" -> "A valid sidecar credential is required.";
      case "READ_ONLY_CONTRACT_REQUIRED" -> "A matching read-only business operation contract is required.";
      case "REQUEST_TOO_LARGE" -> "The request exceeds 64 KiB.";
      case "USERNAME_REQUIRED" -> "The username parameter is required.";
      case "USERNAME_INVALID" -> "The username parameter is invalid.";
      case "PARAMETER_NOT_ALLOWED" -> "The request contains a parameter not approved for this operation.";
      case "MAX_ROWS_INVALID" -> "maxRows must be an integer within the operated limit.";
      case "INACTIVE_DAYS_INVALID" -> "inactiveDays must be an integer between 1 and 3650.";
      case "CLIENT_INVALID" -> "client must contain exactly three digits.";
      case "CLIENT_MISMATCH" -> "The requested client does not match the operated SAP destination.";
      case "USER_TYPE_INVALID" -> "userType is not one of the governed SU01 user types.";
      case "ACCOUNT_STATUS_INVALID" -> "accountStatus is not one of the governed account states.";
      case "DIMENSION_INVALID" -> "dimension must be accountStatus or userType.";
      case "SAP_READ_TIMEOUT" -> "SAP did not complete the governed read within the configured limit.";
      case "REQUEST_INTERRUPTED" -> "The governed read was interrupted before completion.";
      case "SAP_READ_FAILED" -> "SAP could not complete the governed read.";
      default -> "The requested operation is not permitted.";
    };
  }
}
