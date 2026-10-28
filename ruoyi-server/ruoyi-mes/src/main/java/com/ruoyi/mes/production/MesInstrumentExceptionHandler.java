package com.ruoyi.mes.production;

import com.ruoyi.common.exception.ServiceException;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Order(-20)
@RestControllerAdvice(
  assignableTypes = {
    MesInstrumentController.class, MesLegacyInstrumentController.class,
  }
)
public class MesInstrumentExceptionHandler {

  @ExceptionHandler(ServiceException.class)
  public ResponseEntity<Object> failure(ServiceException error) {
    int code = error.getCode() == null ? 400 : error.getCode();
    if (code < 400 || code > 599) code = 400;
    return ResponseEntity.status(code).body(
      Map.of("accepted", false, "code", code, "message", error.getMessage())
    );
  }
}
