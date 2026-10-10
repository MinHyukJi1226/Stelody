package com.stelody.admin.web;

import com.stelody.auth.web.ApiFieldError;
import com.stelody.auth.web.InputErrors;
import java.util.List;

public class AdminCatalogException extends RuntimeException {
  private final int status;
  private final String code;
  private final List<ApiFieldError> fieldErrors;

  public AdminCatalogException(int status, String code, String message) {
    this(status, code, message, List.of());
  }

  public AdminCatalogException(
      int status, String code, String message, List<ApiFieldError> fieldErrors) {
    super(message);
    this.status = status;
    this.code = code;
    this.fieldErrors = List.copyOf(fieldErrors);
  }

  public List<ApiFieldError> fieldErrors() {
    return fieldErrors;
  }

  public static AdminCatalogException invalid(String field, String code) {
    return invalid(List.of(InputErrors.field(field, code)));
  }

  public static AdminCatalogException invalid(List<ApiFieldError> fields) {
    return new AdminCatalogException(400, "INVALID_CATALOG_REQUEST", "입력 내용을 확인해 주세요", fields);
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static AdminCatalogException invalid() {
    return new AdminCatalogException(400, "INVALID_CATALOG_REQUEST", "입력 내용을 확인해 주세요");
  }

  public static AdminCatalogException missing() {
    return new AdminCatalogException(404, "CATALOG_RESOURCE_NOT_FOUND", "관리 대상을 찾을 수 없습니다");
  }

  public static AdminCatalogException conflict() {
    return new AdminCatalogException(409, "CATALOG_VERSION_CONFLICT", "변경된 정보를 다시 조회해 주세요");
  }
}
