package com.moveinsync.ev.planner.objective;

/**
 * Lexicographic plan score. Fewer backups is always better. Cost is compared only when the backup
 * counts match.
 *
 * <p>The second component is {@code changePenaltyMicros * changedAssignments + chargingCostMicros}
 * (D13, D18).
 *
 * @param backupTrips trips sent to backup
 * @param changedAssignments assignments that differ from the plan being repaired; zero on a fresh
 *     plan
 * @param chargingCostMicros total session cost in micro-units
 */
public record ObjectiveScore(int backupTrips, long changedAssignments, long chargingCostMicros) {

  public ObjectiveScore {
    if (backupTrips < 0) {
      throw new IllegalArgumentException("backupTrips must be non-negative: " + backupTrips);
    }
    if (changedAssignments < 0) {
      throw new IllegalArgumentException(
          "changedAssignments must be non-negative: " + changedAssignments);
    }
    if (chargingCostMicros < 0) {
      throw new IllegalArgumentException(
          "chargingCostMicros must be non-negative: " + chargingCostMicros);
    }
  }

  /**
   * Second objective component in micro-units.
   *
   * @param changePenaltyMicros micro-units charged per changed assignment
   * @return penalty plus charging cost
   */
  public long secondaryMicros(long changePenaltyMicros) {
    if (changePenaltyMicros < 0) {
      throw new IllegalArgumentException(
          "changePenaltyMicros must be non-negative: " + changePenaltyMicros);
    }
    long penalty = Math.multiplyExact(changedAssignments, changePenaltyMicros);
    return Math.addExact(penalty, chargingCostMicros);
  }

  /**
   * Compares two scores. A negative result means {@code this} is better.
   *
   * @param other the other score
   * @param changePenaltyMicros micro-units charged per changed assignment
   * @return comparison where lower is better
   */
  public int comparePreferringFewerBackups(ObjectiveScore other, long changePenaltyMicros) {
    int byBackups = Integer.compare(backupTrips, other.backupTrips);
    if (byBackups != 0) {
      return byBackups;
    }
    return Long.compare(
        secondaryMicros(changePenaltyMicros), other.secondaryMicros(changePenaltyMicros));
  }
}
