package com.stelody.auth.web;

/** Public validation detail; never contains rejected values or exception messages. */
public record ApiFieldError(String field, String code, String message) {}
