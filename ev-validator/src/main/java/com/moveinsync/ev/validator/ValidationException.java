package com.moveinsync.ev.validator;

/** Exception thrown when a candidate plan fails independent validation checks. */
public class ValidationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient ValidationReport report;

  public ValidationException(ValidationReport report) {
    super("Plan failed independent validation with " + report.violationCount() + " violation(s)");
    this.report = report;
  }

  public ValidationReport getReport() {
    return report;
  }
}
