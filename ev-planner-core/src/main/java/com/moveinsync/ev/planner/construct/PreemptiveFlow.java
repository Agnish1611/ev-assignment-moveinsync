package com.moveinsync.ev.planner.construct;

import com.moveinsync.ev.domain.hub.Charger;
import com.moveinsync.ev.domain.hub.Hub;
import com.moveinsync.ev.domain.tariff.TariffSchedule;
import com.moveinsync.ev.domain.tariff.UtcTariffWindow;
import com.moveinsync.ev.planner.config.SolverConfig;
import com.moveinsync.ev.planner.input.PlannerInput;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Minimum cost of delivering each session's energy on splittable 60-second slots.
 *
 * <p>A slot is priced at the cheapest instantaneous tariff inside it, and energy that does not fit
 * is priced at zero. Both choices stay under the contiguous tariff walk. Filling cheapest edges
 * first is optimal for this linear transportation problem. Time O(J · H · N) for J sessions, H
 * hubs, and N horizon slots. Space O(J · H · N).
 */
final class PreemptiveFlow {

  static long micros(PlannerInput input, List<FlowJobs.FlowJob> jobs) {
    List<Edge> edges = new ArrayList<>();
    for (FlowJobs.FlowJob job : jobs) {
      if (job.energyWh() > 0L) {
        edges.addAll(edgesFor(input, job));
      }
    }
    return fill(jobs, edges);
  }

  private static List<Edge> edgesFor(PlannerInput input, FlowJobs.FlowJob job) {
    List<Edge> edges = new ArrayList<>();
    for (FlowJobs.Opening opening : job.openings()) {
      Optional<Hub> hub = hub(input, opening.hubId());
      if (hub.isPresent()) {
        edges.addAll(slots(input, job, hub.orElseThrow(), opening));
      }
    }
    return edges;
  }

  private static List<Edge> slots(
      PlannerInput input, FlowJobs.FlowJob job, Hub hub, FlowJobs.Opening opening) {
    List<Edge> edges = new ArrayList<>();
    long cursor = input.horizonStart().getEpochSecond();
    long horizonEnd = input.horizonEnd().getEpochSecond();
    int width = SolverConfig.FLOW_SLOT_SECONDS;
    while (cursor < horizonEnd) {
      long slotEnd = Math.min(horizonEnd, cursor + width);
      long from = Math.max(cursor, opening.earliestInclusive());
      long to = Math.min(slotEnd, opening.latestExclusive());
      if (from < to) {
        Optional<Edge> edge = edge(input, job, hub, cursor, from, to);
        if (edge.isPresent()) {
          edges.add(edge.orElseThrow());
        }
      }
      cursor = slotEnd;
    }
    return edges;
  }

  private static Optional<Edge> edge(
      PlannerInput input, FlowJobs.FlowJob job, Hub hub, long slotStart, long from, long to) {
    Supply supply = supply(input, job, hub, from, to);
    if (supply.onePlugWh() <= 0L) {
      return Optional.empty();
    }
    long perWh = minRate(input, hub, from, to) / 1000L;
    return Optional.of(
        new Edge(
            job.jobId(), slotStart, hub.id().value(), perWh, supply.onePlugWh(), supply.hubWh()));
  }

  private static Supply supply(
      PlannerInput input, FlowJobs.FlowJob job, Hub hub, long from, long to) {
    Instant start = Instant.ofEpochSecond(from);
    Instant end = Instant.ofEpochSecond(to);
    List<Integer> powers = new ArrayList<>();
    for (Charger charger : input.chargers()) {
      if (charger.hubId().equals(hub.id()) && charger.isAvailableDuring(start, end)) {
        powers.add(Math.min(charger.powerKw(), job.maxChargeKw()));
      }
    }
    if (powers.isEmpty()) {
      return new Supply(0L, 0L);
    }
    powers.sort(Comparator.reverseOrder());
    int used = Math.min(powers.size(), hub.chargerCount());
    long sumKw = 0L;
    for (int index = 0; index < used; index++) {
      sumKw += powers.get(index);
    }
    long seconds = to - from;
    long one = Math.multiplyExact(seconds, (long) powers.get(0) * 1000L) / 3600L;
    long all = Math.multiplyExact(seconds, sumKw * 1000L) / 3600L;
    return new Supply(one, all);
  }

  private static long minRate(PlannerInput input, Hub hub, long from, long to) {
    List<UtcTariffWindow> windows = windows(input, hub);
    if (windows.isEmpty()) {
      return 0L;
    }
    long min = Long.MAX_VALUE;
    for (long second = from; second < to; second++) {
      long sum = rateAt(windows, second);
      if (sum < min) {
        min = sum;
      }
    }
    return min == Long.MAX_VALUE ? 0L : min;
  }

  private static long rateAt(List<UtcTariffWindow> windows, long second) {
    long sum = 0L;
    for (UtcTariffWindow window : windows) {
      long start = window.startUtc().getEpochSecond();
      long end = window.endUtc().getEpochSecond();
      if (second >= start && second < end) {
        sum = Math.addExact(sum, window.ratePerKwhMicros());
      }
    }
    return sum;
  }

  private static List<UtcTariffWindow> windows(PlannerInput input, Hub hub) {
    List<UtcTariffWindow> windows = new ArrayList<>();
    for (TariffSchedule schedule : input.tariffs()) {
      if (schedule.hubId().equals(hub.id())) {
        windows.addAll(schedule.windows());
      }
    }
    return windows;
  }

  private static long fill(List<FlowJobs.FlowJob> jobs, List<Edge> edges) {
    edges.sort(
        Comparator.comparingLong(Edge::perWh)
            .thenComparing(Edge::jobId)
            .thenComparingLong(Edge::slotStart)
            .thenComparing(Edge::hubId));
    Map<String, Long> left = new HashMap<>();
    for (FlowJobs.FlowJob job : jobs) {
      left.merge(job.jobId(), job.energyWh(), Math::addExact);
    }
    Map<String, Long> slots = new HashMap<>();
    long cost = 0L;
    for (Edge edge : edges) {
      slots.putIfAbsent(edge.slotKey(), edge.hubWh());
      long jobLeft = left.getOrDefault(edge.jobId(), 0L);
      long slotLeft = slots.getOrDefault(edge.slotKey(), 0L);
      long take = Math.min(jobLeft, Math.min(edge.onePlugWh(), slotLeft));
      if (take > 0L) {
        left.put(edge.jobId(), jobLeft - take);
        slots.put(edge.slotKey(), slotLeft - take);
        cost = Math.addExact(cost, Math.multiplyExact(take, edge.perWh()));
      }
    }
    return cost;
  }

  private static Optional<Hub> hub(PlannerInput input, String hubId) {
    for (Hub hub : input.hubs()) {
      if (hub.id().value().equals(hubId)) {
        return Optional.of(hub);
      }
    }
    return Optional.empty();
  }

  private record Supply(long onePlugWh, long hubWh) {}

  private record Edge(
      String jobId, long slotStart, String hubId, long perWh, long onePlugWh, long hubWh) {

    String slotKey() {
      return hubId + ":" + slotStart;
    }
  }
}
