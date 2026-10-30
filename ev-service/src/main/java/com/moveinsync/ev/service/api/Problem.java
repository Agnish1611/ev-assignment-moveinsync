package com.moveinsync.ev.service.api;

/** RFC 7807 body. There is no detail field, so a stack trace cannot leak. */
public record Problem(String type, String title, int status, String code, String correlationId) {}
