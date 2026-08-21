package com.logali.rfcguard;

import java.util.concurrent.CountDownLatch;

public final class Main {
  private Main() {}

  public static void main(String[] arguments) throws Exception {
    Configuration configuration = Configuration.fromEnvironment();
    JcoSapAdapter adapter = new JcoSapAdapter(configuration);
    HttpApi api = new HttpApi(configuration, adapter);
    Runtime.getRuntime().addShutdownHook(new Thread(api::close));
    api.start();
    System.out.println("SAP RFC Guard JCo sidecar listening on port " + configuration.port());
    new CountDownLatch(1).await();
  }
}
