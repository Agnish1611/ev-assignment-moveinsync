package com.moveinsync.ev.service.api;

/** The caller taken from the token. The tenant is never read from the request. */
public record Caller(String tenant, String userId, String role) {}
