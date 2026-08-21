package com.logali.rfcguard;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

public final class HealthCheck {
  private HealthCheck() {}

  public static void main(String[] arguments) throws Exception {
    String token = System.getenv("RFC_GUARD_API_TOKEN");
    String port = System.getenv().getOrDefault("PORT", "8080");
    if (token == null || token.isBlank()) System.exit(1);
    System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
    var trustManager = new X509TrustManager() {
      @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
      @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
      @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
    };
    SSLContext ssl = SSLContext.getInstance("TLS");
    ssl.init(null, new X509TrustManager[]{trustManager}, new SecureRandom());
    var request = HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + port + "/v1/health"))
        .timeout(Duration.ofSeconds(4))
        .header("Authorization", "Bearer " + token)
        .GET().build();
    int status = HttpClient.newBuilder().sslContext(ssl).build()
        .send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    if (status != 200) System.exit(1);
  }
}
