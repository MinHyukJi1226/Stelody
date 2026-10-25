package com.stelody.review.web;

import com.stelody.auth.web.ApiFieldError;
import java.util.List;

public class ReviewException extends RuntimeException {
  private final int status;
  private final String code;
  private final List<ApiFieldError> fieldErrors;

  public ReviewException(int status, String code, String message) {
    this(status, code, message, List.of());
  }

  public ReviewException(int status, String code, String message, List<ApiFieldError> fields) {
    super(message);
    this.status = status;
    this.code = code;
    this.fieldErrors = List.copyOf(fields);
  }

  public List<ApiFieldError> fieldErrors() {
    return fieldErrors;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static ReviewException invalid() {
    return new ReviewException(400, "INVALID_REVIEW_REQUEST", "검토 요청 값을 확인해 주세요");
  }

  public static ReviewException conflict() {
    return new ReviewException(409, "REVIEW_VERSION_CONFLICT", "검토 후보가 변경되었습니다. 새로고침해 주세요");
  }
}
