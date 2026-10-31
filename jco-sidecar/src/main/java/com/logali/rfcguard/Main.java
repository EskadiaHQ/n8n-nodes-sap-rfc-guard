package com.logali.rfcguard;

import java.util.concurrent.CountDownLatch;

public final class Main {
  private Main() {}

  public static void main(String[] arguments) throws Exception {
    Configuration configuration = Configuration.fromEnvironment();
    JcoSapAdapter adapter = new JcoSapAdapter(configuration);
    String catalogPath = System.getenv().getOrDefault("RFC_GUARD_FUNCTION_CATALOG", "").trim();
    FunctionCatalog catalog = catalogPath.isEmpty() ? null : new FunctionCatalog(
        new CatalogPolicySource(java.nio.file.Path.of(catalogPath)),
        new JcoCatalogBackend(adapter, System.getenv("SAP_LANG")), configuration.maxRows(),
        FunctionCatalog.Settings.fromEnvironment());
    HttpApi api = new HttpApi(configuration, adapter, catalog);
    Runtime.getRuntime().addShutdownHook(new Thread(api::close));
    api.start();
    System.out.println("SAP RFC Guard JCo sidecar listening on port " + configuration.port());
    new CountDownLatch(1).await();
  }
}
