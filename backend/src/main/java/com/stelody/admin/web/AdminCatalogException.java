package com.stelody.admin.web;

public class AdminCatalogException extends RuntimeException {
  private final int status;
  private final String code;

  public AdminCatalogException(int status, String code, String message) {
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
