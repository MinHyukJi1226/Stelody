package com.stelody.catalog.web;

public class CatalogException extends RuntimeException {
  private final int status;
  private final String code;

  public CatalogException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
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
