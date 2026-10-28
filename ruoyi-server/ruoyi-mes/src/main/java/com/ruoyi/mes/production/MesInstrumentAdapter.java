package com.ruoyi.mes.production;

import java.util.Map;

/** Register a Spring bean per protocol; shared authentication, transaction and receipt logging remain unchanged. */
public interface MesInstrumentAdapter {
  String protocol();
  Map<String, Object> normalize(String operation, Map<String, Object> body);
}
