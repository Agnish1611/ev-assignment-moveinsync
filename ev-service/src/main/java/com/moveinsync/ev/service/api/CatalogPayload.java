package com.moveinsync.ev.service.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.ev.service.api.ApiModels.Intake;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Reads one catalog object or a batch. One bad element does not reject the batch. */
public final class CatalogPayload {

  private final ObjectMapper mapper;
  private final int maxItems;

  public CatalogPayload(ObjectMapper mapper, int maxItems) {
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    if (maxItems <= 0) {
      throw new IllegalArgumentException("maxItems must be positive");
    }
    this.maxItems = maxItems;
  }

  public <T> T one(JsonNode body, Class<T> type) {
    if (body == null || !body.isObject()) {
      throw new ApiException(400, "INVALID_BODY", "The body is not valid");
    }
    try {
      return mapper.treeToValue(body, type);
    } catch (JsonProcessingException ex) {
      throw new ApiException(400, "INVALID_BODY", "The body is not valid");
    }
  }

  public <T> List<Intake<T>> rows(JsonNode body, Class<T> type) {
    if (body == null || !body.isArray()) {
      throw new ApiException(400, "INVALID_BODY", "The body is not valid");
    }
    if (body.size() == 0 || body.size() > maxItems) {
      throw new ApiException(400, "BULK_LIMIT", "The batch size is not valid");
    }
    List<Intake<T>> found = new ArrayList<>();
    for (JsonNode node : body) {
      found.add(element(node, type));
    }
    return found;
  }

  private <T> Intake<T> element(JsonNode node, Class<T> type) {
    if (node == null || !node.isObject()) {
      return new Intake<>(null, "INVALID_BODY");
    }
    try {
      return new Intake<>(mapper.treeToValue(node, type), "");
    } catch (JsonProcessingException ex) {
      return new Intake<>(null, "INVALID_BODY");
    }
  }
}
