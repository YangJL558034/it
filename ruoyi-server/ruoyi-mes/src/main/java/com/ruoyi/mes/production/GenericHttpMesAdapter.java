package com.ruoyi.mes.production;

import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class GenericHttpMesAdapter implements MesInstrumentAdapter {

  @Override
  public String protocol() {
    return "generic-v1";
  }

  @Override
  public Map<String, Object> normalize(
    String operation,
    Map<String, Object> body
  ) {
    return body;
  }
}
