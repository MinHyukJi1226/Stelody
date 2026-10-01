package com.stelody.collector.domain;

public final class CollectionFailure extends RuntimeException {
  private final String code;
  private final boolean retryable;

  public CollectionFailure(String code, boolean retryable) {
    super(code);
    this.code = code;
    this.retryable = retryable;
  }

  public String code() {
    return code;
  }

  public boolean retryable() {
    return retryable;
  }
}
