package com.moveinsync.ev.service.plan;

import java.util.Map;
import java.util.TreeSet;

/** Trip moves between two published versions, one trip per three lines, ordered by trip id. */
final class PlanDiffs {

  private PlanDiffs() {
    throw new IllegalStateException("utility");
  }

  static String between(Map<String, String> before, Map<String, String> after) {
    TreeSet<String> ids = new TreeSet<>();
    ids.addAll(before.keySet());
    ids.addAll(after.keySet());
    StringBuilder text = new StringBuilder();
    for (String id : ids) {
      String left = before.getOrDefault(id, "");
      String right = after.getOrDefault(id, "");
      if (!left.equals(right)) {
        text.append(id).append('\n').append(left).append('\n').append(right).append('\n');
      }
    }
    return text.toString();
  }
}
