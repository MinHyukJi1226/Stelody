package com.stelody.user.web;

import com.stelody.auth.web.ApiProblems;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(basePackages = "com.stelody.user.controller")
public class AccountExceptionHandler {
  private final ApiProblems problems;

  public AccountExceptionHandler(ApiProblems problems) {
    this.problems = problems;
  }

  @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
  void malformed(HttpServletRequest request, HttpServletResponse response) throws IOException {
    problems.write(
        request, response, 400, "INVALID_REAUTHENTICATION_REQUEST", "재인증 요청 JSON 형식이 올바르지 않습니다");
  }

  @ExceptionHandler(AccountException.class)
  void account(AccountException e, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    response.setHeader("Referrer-Policy", "no-referrer");
    problems.write(request, response, e.status(), e.code(), e.getMessage());
  }

  @ExceptionHandler({DataAccessException.class, TransactionException.class})
  void storage(HttpServletRequest request, HttpServletResponse response) throws IOException {
    problems.write(
        request,
        response,
        503,
        "ACCOUNT_STORAGE_UNAVAILABLE",
        "계정 처리를 완료하지 못했습니다. 잠시 후 다시 시도해 주세요");
  }
}
