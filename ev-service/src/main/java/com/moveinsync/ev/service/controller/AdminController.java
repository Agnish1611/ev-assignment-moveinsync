package com.moveinsync.ev.service.controller;

import com.moveinsync.ev.service.api.ApiModels.ChargerRequest;
import com.moveinsync.ev.service.api.ApiModels.Created;
import com.moveinsync.ev.service.api.ApiModels.HubRequest;
import com.moveinsync.ev.service.api.ApiModels.TariffRequest;
import com.moveinsync.ev.service.api.ApiModels.TripRequest;
import com.moveinsync.ev.service.api.ApiModels.UserRequest;
import com.moveinsync.ev.service.api.ApiModels.VehicleRequest;
import com.moveinsync.ev.service.api.CallerResolver;
import com.moveinsync.ev.service.api.CatalogService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Fleet catalog. Admin only, and the tenant comes from the token. */
@RestController
@RequestMapping("/v1/admin")
@ConditionalOnWebApplication
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

  private final CallerResolver callers;
  private final CatalogService catalog;

  public AdminController(CallerResolver callers, CatalogService catalog) {
    this.callers = callers;
    this.catalog = catalog;
  }

  @PostMapping("/hubs")
  public ResponseEntity<Created> hub(
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody HubRequest request) {
    return catalog.hub(callers.require(), key, request);
  }

  @PostMapping("/chargers")
  public ResponseEntity<Created> charger(
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody ChargerRequest request) {
    return catalog.charger(callers.require(), key, request);
  }

  @PostMapping("/vehicles")
  public ResponseEntity<Created> vehicle(
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody VehicleRequest request) {
    return catalog.vehicle(callers.require(), key, request);
  }

  @PostMapping("/trips")
  public ResponseEntity<Created> trip(
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody TripRequest request) {
    return catalog.trip(callers.require(), key, request);
  }

  @PostMapping("/tariffs")
  public ResponseEntity<Created> tariff(
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody TariffRequest request) {
    return catalog.tariff(callers.require(), key, request);
  }

  @PostMapping("/users")
  public ResponseEntity<Created> user(
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody UserRequest request) {
    return catalog.user(callers.require(), key, request);
  }
}
