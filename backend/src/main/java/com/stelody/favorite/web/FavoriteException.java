package com.stelody.favorite.web;

public class FavoriteException extends RuntimeException {
  private final int status;
  private final String code;

  public FavoriteException(int status, String code, String message) {
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

  public static FavoriteException invalid() {
    return new FavoriteException(400, "INVALID_FAVORITE_QUERY", "곡 ID, 목록 크기 또는 커서를 확인해 주세요");
  }
}
