package com.moveinsync.ev.service.controller;

import com.moveinsync.ev.service.api.ApiModels.PlanDiffView;
import com.moveinsync.ev.service.api.ApiModels.PlanHeadView;
import com.moveinsync.ev.service.api.ApiModels.PlanJobAccepted;
import com.moveinsync.ev.service.api.ApiModels.PlanJobRequest;
import com.moveinsync.ev.service.api.ApiModels.PlanJobStatus;
import com.moveinsync.ev.service.api.ApiModels.PlanVersionView;
import com.moveinsync.ev.service.api.ApiModels.TimelineView;
import com.moveinsync.ev.service.api.ApiModels.UnassignedView;
import com.moveinsync.ev.service.api.CallerResolver;
import com.moveinsync.ev.service.api.PlanJobs;
import com.moveinsync.ev.service.api.PlanQuery;
import com.moveinsync.ev.service.api.ProcessRole;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Plan reads and the accepted-job endpoint. Controllers do not touch repositories. */
@RestController
@RequestMapping("/v1")
@ConditionalOnWebApplication
@ConditionalOnExpression(ProcessRole.API)
public class PlanController {

  private final CallerResolver callers;
  private final PlanQuery plans;
  private final PlanJobs jobs;

  public PlanController(CallerResolver callers, PlanQuery plans, PlanJobs jobs) {
    this.callers = callers;
    this.plans = plans;
    this.jobs = jobs;
  }

  @PostMapping("/plans")
  @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
  public ResponseEntity<PlanJobAccepted> create(
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody PlanJobRequest request) {
    return jobs.accept(callers.require(), key, request);
  }

  @GetMapping("/plans/jobs/{jobId}")
  @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
  public PlanJobStatus job(@PathVariable("jobId") String jobId) {
    return jobs.status(callers.require(), jobId);
  }

  @GetMapping("/plans/current")
  @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
  public PlanHeadView current(@RequestParam("shard") String shard) {
    return plans.current(callers.require(), shard);
  }

  @GetMapping("/plans/{version}")
  @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
  public PlanVersionView version(
      @PathVariable("version") long version, @RequestParam("shard") String shard) {
    return plans.version(callers.require(), shard, version);
  }

  @GetMapping("/plans/{version}/diff")
  @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
  public PlanDiffView diff(
      @PathVariable("version") long version,
      @RequestParam("shard") String shard,
      @RequestParam("from") long from) {
    return plans.diff(callers.require(), shard, version, from);
  }

  @GetMapping(value = "/plans/{version}/validation-report", produces = MediaType.TEXT_PLAIN_VALUE)
  @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
  public ResponseEntity<String> validationReport(
      @PathVariable("version") long version, @RequestParam("shard") String shard) {
    String report = plans.validationReport(callers.require(), shard, version);
    return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(report);
  }

  @GetMapping("/plans/{version}/unassigned")
  @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
  public List<UnassignedView> unassigned(
      @PathVariable("version") long version, @RequestParam("shard") String shard) {
    return plans.unassigned(callers.require(), shard, version);
  }

  @GetMapping("/cabs/{id}/timeline")
  @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
  public TimelineView timeline(
      @PathVariable("id") String cabId,
      @RequestParam("shard") String shard,
      @RequestParam(name = "version", required = false) Long version) {
    return plans.timeline(callers.require(), cabId, shard, version);
  }
}
