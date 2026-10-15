package com.stelody.catalog.web;

import com.stelody.auth.web.ApiFieldError;
import java.util.List;

public class CatalogException extends RuntimeException {
  private final int status;
  private final String code;
  private final List<ApiFieldError> fieldErrors;

  public CatalogException(int status, String code, String message) {
    this(status, code, message, List.of());
  }

  public CatalogException(
      int status, String code, String message, List<ApiFieldError> fieldErrors) {
    super(message);
    this.fieldErrors =
        fieldErrors.stream()
            .distinct()
            .sorted(
                java.util.Comparator.comparing(ApiFieldError::field)
                    .thenComparing(ApiFieldError::code))
            .toList();
    this.status = status;
    this.code = code;
  }

  public List<ApiFieldError> fieldErrors() {
    return fieldErrors;
  }

  public static CatalogException invalid(List<ApiFieldError> fields) {
    return new CatalogException(400, "INVALID_CATALOG_QUERY", "검색 조건을 확인해 주세요", fields);
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static CatalogException invalid() {
    return new CatalogException(400, "INVALID_CATALOG_QUERY", "검색 조건 또는 커서를 확인해 주세요");
  }

  public static CatalogException missing() {
    return new CatalogException(404, "CATALOG_NOT_FOUND", "공개된 정보를 찾을 수 없습니다");
  }
}
