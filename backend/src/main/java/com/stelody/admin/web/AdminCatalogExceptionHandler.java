package com.stelody.admin.web;

import com.stelody.admin.controller.CatalogAdminController;
import com.stelody.auth.web.ApiProblems;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.dao.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = CatalogAdminController.class)
public class AdminCatalogExceptionHandler {
  private final ApiProblems problems;

  public AdminCatalogExceptionHandler(ApiProblems problems) {
    this.problems = problems;
  }

  @ExceptionHandler(AdminCatalogException.class)
  public void business(AdminCatalogException e, HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    problems.write(req, res, e.status(), e.code(), e.getMessage());
  }

  @ExceptionHandler({
    OptimisticLockingFailureException.class,
    jakarta.persistence.OptimisticLockException.class
  })
  public void conflict(Exception e, HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    business(AdminCatalogException.conflict(), req, res);
  }

  @ExceptionHandler({
    DataIntegrityViolationException.class,
    org.hibernate.exception.ConstraintViolationException.class
  })
  public void duplicate(Exception e, HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    business(
        new AdminCatalogException(409, "CATALOG_DATA_CONFLICT", "중복 또는 연결된 항목을 확인해 주세요"), req, res);
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    MethodArgumentTypeMismatchException.class,
    HttpMessageNotReadableException.class,
    MissingServletRequestParameterException.class
  })
  public void invalid(Exception e, HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    business(AdminCatalogException.invalid(), req, res);
  }
}
