package com.moveinsync.ev.validator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable structured report produced by the independent plan validator.
 *
 * <p>A plan is valid if and only if {@link #isValid()} returns true (i.e. zero violations).
 *
 * @param violations list of invariant violations included in the report (may be capped)
 * @param totalViolationsCount true uncapped total count of all violations detected
 * @param isTruncated true if the violations list was capped by a reporting threshold
 * @param totalVehiclesChecked count of fleet vehicles evaluated
 * @param totalTripsChecked count of assigned trips evaluated
 * @param totalSessionsChecked count of charging sessions evaluated
 */
public record ValidationReport(
    List<PlanViolation> violations,
    int totalViolationsCount,
    boolean isTruncated,
    int totalVehiclesChecked,
    int totalTripsChecked,
    int totalSessionsChecked) {

  public ValidationReport {
    Objects.requireNonNull(violations, "violations list must not be null");
    violations = Collections.unmodifiableList(new ArrayList<>(violations));
  }

  public ValidationReport(
      List<PlanViolation> violations,
      int totalVehiclesChecked,
      int totalTripsChecked,
      int totalSessionsChecked) {
    this(
        violations,
        violations.size(),
        false,
        totalVehiclesChecked,
        totalTripsChecked,
        totalSessionsChecked);
  }

  public static ValidationReport success(
      int totalVehiclesChecked, int totalTripsChecked, int totalSessionsChecked) {
    return new ValidationReport(
        List.of(), 0, false, totalVehiclesChecked, totalTripsChecked, totalSessionsChecked);
  }

  public static ValidationReport createCapped(
      List<PlanViolation> allViolations,
      int maxReportCap,
      int totalVehiclesChecked,
      int totalTripsChecked,
      int totalSessionsChecked) {
    int total = allViolations.size();
    if (maxReportCap <= 0 || total <= maxReportCap) {
      return new ValidationReport(
          allViolations,
          total,
          false,
          totalVehiclesChecked,
          totalTripsChecked,
          totalSessionsChecked);
    }
    List<PlanViolation> capped = allViolations.subList(0, maxReportCap);
    return new ValidationReport(
        capped, total, true, totalVehiclesChecked, totalTripsChecked, totalSessionsChecked);
  }

  /**
   * Checks whether the plan passed all validation checks with zero violations.
   *
   * @return true if valid, false otherwise
   */
  public boolean isValid() {
    return violations.isEmpty();
  }

  /**
   * Returns the count of violations contained in this report.
   *
   * @return violation count
   */
  public int violationCount() {
    return violations.size();
  }

  /**
   * Filters violations by specific rule ID.
   *
   * @param ruleId rule identifier
   * @return list of matching violations
   */
  public List<PlanViolation> violationsFor(RuleId ruleId) {
    Objects.requireNonNull(ruleId, "ruleId must not be null");
    return violations.stream().filter(v -> v.ruleId() == ruleId).toList();
  }

  /**
   * Asserts that the plan is completely valid, throwing {@link ValidationException} if any
   * violation exists.
   *
   * @throws ValidationException if the report contains one or more violations
   */
  public void assertValid() {
    if (!isValid()) {
      throw new ValidationException(this);
    }
  }

  /**
   * Generates a structured multi-line text summary of the validation findings.
   *
   * @return human-readable diagnostic summary
   */
  public String toDetailedSummary() {
    StringBuilder sb = new StringBuilder();
    sb.append("Validation Summary: ");
    if (isValid()) {
      sb.append("PASSED (All invariants H1-H6 satisfied)\n");
    } else {
      sb.append("FAILED with ").append(totalViolationsCount).append(" violation(s)");
      if (isTruncated) {
        sb.append(" [Report truncated to ").append(violations.size()).append("]");
      }
      sb.append("\n");
    }
    sb.append("  Vehicles evaluated: ").append(totalVehiclesChecked).append("\n");
    sb.append("  Assigned trips evaluated: ").append(totalTripsChecked).append("\n");
    sb.append("  Charging sessions evaluated: ").append(totalSessionsChecked).append("\n");

    if (!isValid()) {
      sb.append("Violations:\n");
      for (PlanViolation v : violations) {
        sb.append("  - [").append(v.ruleId()).append("] ").append(v.message()).append("\n");
      }
    }
    return sb.toString();
  }
}
