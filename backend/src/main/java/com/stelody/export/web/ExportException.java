package com.stelody.export.web;

public class ExportException extends RuntimeException {
  private final int status;
  private final String code;

  public ExportException(int status, String code) {
    super("YouTube 내보내기 상태를 확인해 주세요");
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static ExportException invalid() {
    return new ExportException(400, "INVALID_EXPORT_REQUEST");
  }

  public static ExportException missing() {
    return new ExportException(404, "EXPORT_NOT_FOUND");
  }
}
