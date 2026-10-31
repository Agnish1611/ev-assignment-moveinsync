package com.moveinsync.ev.service.controller;

import com.moveinsync.ev.service.api.ApiModels.Accepted;
import com.moveinsync.ev.service.api.ApiModels.BatteryRequest;
import com.moveinsync.ev.service.api.ApiModels.ChargerFailRequest;
import com.moveinsync.ev.service.api.ApiModels.ReplanRequest;
import com.moveinsync.ev.service.api.CallerResolver;
import com.moveinsync.ev.service.api.IngressService;
import com.moveinsync.ev.service.api.ProcessRole;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Operational events. The controller does not solve. */
@RestController
@ConditionalOnWebApplication
@ConditionalOnExpression(ProcessRole.API)
public class EventController {

  private final CallerResolver callers;
  private final IngressService ingress;

  public EventController(CallerResolver callers, IngressService ingress) {
    this.callers = callers;
    this.ingress = ingress;
  }

  @PostMapping("/v1/events/battery")
  @PreAuthorize("hasAnyRole('OPERATOR','ADMIN','DEVICE')")
  public ResponseEntity<Accepted> battery(
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody BatteryRequest request) {
    return ingress.battery(callers.require(), key, request);
  }

  @PostMapping("/v1/chargers/{id}/fail")
  @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
  public ResponseEntity<Accepted> chargerFailure(
      @PathVariable("id") String chargerId,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody ChargerFailRequest request) {
    return ingress.chargerFailure(callers.require(), key, chargerId, request);
  }

  @PostMapping("/v1/replans")
  @PreAuthorize("hasAnyRole('OPERATOR','ADMIN')")
  public ResponseEntity<Accepted> replan(
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody ReplanRequest request) {
    return ingress.replan(callers.require(), key, request);
  }
}
