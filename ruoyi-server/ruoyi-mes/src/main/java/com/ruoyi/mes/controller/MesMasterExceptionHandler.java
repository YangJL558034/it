package com.ruoyi.mes.controller;

import com.ruoyi.common.core.domain.AjaxResult;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.*;

@Order(-10)
@RestControllerAdvice(assignableTypes = MesMasterController.class)
public class MesMasterExceptionHandler {

  @ExceptionHandler(DataIntegrityViolationException.class)
  public AjaxResult conflict(DataIntegrityViolationException error) {
    return AjaxResult.error(
      409,
      "编码重复或资料仍有关联，请刷新后检查；原记录未改变"
    );
  }
}
