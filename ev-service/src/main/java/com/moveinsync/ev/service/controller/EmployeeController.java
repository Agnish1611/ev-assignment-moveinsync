package com.moveinsync.ev.service.controller;

import com.moveinsync.ev.service.api.ApiModels.OwnTrips;
import com.moveinsync.ev.service.api.CallerResolver;
import com.moveinsync.ev.service.api.PlanQuery;
import com.moveinsync.ev.service.api.ProcessRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own trips. The employee id is the token subject. */
@RestController
@ConditionalOnWebApplication
@ConditionalOnExpression(ProcessRole.API)
public class EmployeeController {

  private final CallerResolver callers;
  private final PlanQuery plans;

  public EmployeeController(CallerResolver callers, PlanQuery plans) {
    this.callers = callers;
    this.plans = plans;
  }

  @GetMapping("/v1/me/trips")
  @PreAuthorize("hasRole('EMPLOYEE')")
  public OwnTrips trips() {
    return plans.ownTrips(callers.require());
  }
}
