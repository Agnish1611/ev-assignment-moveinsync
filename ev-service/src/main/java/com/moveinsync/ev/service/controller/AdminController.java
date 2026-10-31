package com.moveinsync.ev.service.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.ev.service.api.ApiModels.ChargerRequest;
import com.moveinsync.ev.service.api.ApiModels.HubRequest;
import com.moveinsync.ev.service.api.ApiModels.TariffRequest;
import com.moveinsync.ev.service.api.ApiModels.TripRequest;
import com.moveinsync.ev.service.api.ApiModels.UserRequest;
import com.moveinsync.ev.service.api.ApiModels.VehicleRequest;
import com.moveinsync.ev.service.api.ApiSettings;
import com.moveinsync.ev.service.api.CallerResolver;
import com.moveinsync.ev.service.api.CatalogPayload;
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
  private final CatalogPayload payload;

  public AdminController(
      CallerResolver callers, CatalogService catalog, ObjectMapper mapper, ApiSettings settings) {
    this.callers = callers;
    this.catalog = catalog;
    this.payload = new CatalogPayload(mapper, settings.maxBulkItems());
  }

  @PostMapping("/hubs")
  public ResponseEntity<?> hub(
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody JsonNode body) {
    if (body != null && body.isArray()) {
      return catalog.hubs(callers.require(), key, payload.rows(body, HubRequest.class));
    }
    return catalog.hub(callers.require(), key, payload.one(body, HubRequest.class));
  }

  @PostMapping("/chargers")
  public ResponseEntity<?> charger(
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody JsonNode body) {
    if (body != null && body.isArray()) {
      return catalog.chargers(callers.require(), key, payload.rows(body, ChargerRequest.class));
    }
    return catalog.charger(callers.require(), key, payload.one(body, ChargerRequest.class));
  }

  @PostMapping("/vehicles")
  public ResponseEntity<?> vehicle(
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody JsonNode body) {
    if (body != null && body.isArray()) {
      return catalog.vehicles(callers.require(), key, payload.rows(body, VehicleRequest.class));
    }
    return catalog.vehicle(callers.require(), key, payload.one(body, VehicleRequest.class));
  }

  @PostMapping("/trips")
  public ResponseEntity<?> trip(
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody JsonNode body) {
    if (body != null && body.isArray()) {
      return catalog.trips(callers.require(), key, payload.rows(body, TripRequest.class));
    }
    return catalog.trip(callers.require(), key, payload.one(body, TripRequest.class));
  }

  @PostMapping("/tariffs")
  public ResponseEntity<?> tariff(
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody JsonNode body) {
    if (body != null && body.isArray()) {
      return catalog.tariffs(callers.require(), key, payload.rows(body, TariffRequest.class));
    }
    return catalog.tariff(callers.require(), key, payload.one(body, TariffRequest.class));
  }

  @PostMapping("/users")
  public ResponseEntity<?> user(
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody JsonNode body) {
    if (body != null && body.isArray()) {
      return catalog.users(callers.require(), key, payload.rows(body, UserRequest.class));
    }
    return catalog.user(callers.require(), key, payload.one(body, UserRequest.class));
  }
}
