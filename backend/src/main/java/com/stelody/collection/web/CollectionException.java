package com.stelody.collection.web;

public class CollectionException extends RuntimeException {
  private final int status;
  private final String code;

  public CollectionException(int status, String code) {
    super("수집 운영 요청을 처리할 수 없습니다");
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static CollectionException invalid() {
    return new CollectionException(400, "INVALID_COLLECTION_REQUEST");
  }
}
