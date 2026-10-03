package com.stelody.user.web;

public class AccountException extends RuntimeException {
  private final int status;
  private final String code;

  public AccountException(int status, String code, String message) {
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

  public static AccountException reauthenticationRequired() {
    return new AccountException(409, "REAUTHENTICATION_REQUIRED", "같은 Google 계정으로 다시 인증해 주세요");
  }
}
