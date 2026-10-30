package com.moveinsync.ev.service.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Turns failures into problem+json. Exception text is never copied into the body. */
@RestControllerAdvice
@ConditionalOnWebApplication
public class ApiErrors {

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<Problem> denied(HttpServletRequest request) {
    return problem(403, "FORBIDDEN", "The role is not allowed", request);
  }

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<Problem> api(ApiException failure, HttpServletRequest request) {
    return problem(failure.status(), failure.code(), failure.title(), request);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Problem> malformed(HttpServletRequest request) {
    return problem(400, "MALFORMED_BODY", "The body is not valid", request);
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<Problem> missing(
      MissingServletRequestParameterException failure, HttpServletRequest request) {
    if ("shard".equals(failure.getParameterName())) {
      return problem(400, "SHARD_REQUIRED", "shard is required", request);
    }
    return problem(400, "MISSING_PARAMETER", "A required parameter is missing", request);
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<Problem> typeMismatch(HttpServletRequest request) {
    return problem(400, "INVALID_VERSION", "The plan version must be positive", request);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Problem> unexpected(HttpServletRequest request) {
    return problem(500, "INTERNAL", "Request failed", request);
  }

  private ResponseEntity<Problem> problem(
      int status, String code, String title, HttpServletRequest request) {
    String correlation = Correlation.resolve(request);
    Problem body = new Problem("about:blank", title, status, code, correlation);
    return ResponseEntity.status(status)
        .contentType(MediaType.parseMediaType("application/problem+json"))
        .header(Correlation.HEADER, correlation)
        .body(body);
  }
}
