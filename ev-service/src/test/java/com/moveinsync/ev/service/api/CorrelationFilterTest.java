package com.moveinsync.ev.service.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** An email or a coordinate in the header is replaced before it reaches the log context. */
class CorrelationFilterTest {

  @Test
  void personalTextDoesNotEnterTheLogContext() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(Correlation.HEADER, "person@example.com");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicReference<String> seen = new AtomicReference<>();
    FilterChain chain = (req, res) -> seen.set(MDC.get("correlationId"));

    new CorrelationFilter().doFilter(request, response, chain);

    assertThat(seen.get()).doesNotContain("@").doesNotContain(".");
    assertThat(response.getHeader(Correlation.HEADER)).isEqualTo(seen.get());
    assertThat(MDC.get("correlationId")).isNull();
  }
}
