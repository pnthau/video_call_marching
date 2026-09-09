package com.example.videocall_marching_language.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;

@SpringBootTest
@AutoConfigureMockMvc
@Import(HttpMetricsIntegrationTests.LifecycleProbeConfiguration.class)
class HttpMetricsIntegrationTests {

    private static final Set<String> ALLOWED_TAGS = Set.of("method", "uri", "status", "outcome", "exception", "error");
    private static final Set<String> FORBIDDEN_TAGS = Set.of("principal", "session", "correlationId", "correlation_id", "user", "userId");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private LifecycleProbeController lifecycleProbeController;

    @Test
    void standardHttpMetricsRecordFinalStatusAndBoundedRouteTags() throws Exception {
        String suppliedCorrelationId = "client-controlled-id";
        mockMvc.perform(get("/profile?secretQuery=not-a-tag")
                        .header("X-Correlation-ID", suppliedCorrelationId))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("X-Correlation-ID", not(equalTo(suppliedCorrelationId))))
                .andExpect(header().string("X-Correlation-ID", matchesPattern("[A-Za-z0-9._-]{1,64}")));
        mockMvc.perform(get("/does-not-exist/secret?rawQuery=not-a-tag"))
                .andExpect(status().isNotFound());

        var meters = meterRegistry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals("http.server.requests"))
                .toList();
        assertThat(meters).isNotEmpty();
        assertThat(meters.stream().flatMap(meter -> meter.getId().getTags().stream())
                .anyMatch(tag -> tag.getKey().equals("status") && tag.getValue().equals("302")))
                .isTrue();
        assertThat(meters).allSatisfy(meter -> {
            Set<String> tagKeys = meter.getId().getTags().stream()
                    .map(Tag::getKey)
                    .collect(java.util.stream.Collectors.toSet());
            assertThat(tagKeys).contains("method", "status", "uri", "outcome");
            assertThat(tagKeys).containsAnyOf("exception", "error");
            assertThat(tagKeys).containsAnyElementsOf(ALLOWED_TAGS);
            assertThat(tagKeys).doesNotContainAnyElementsOf(FORBIDDEN_TAGS);
            meter.getId().getTags().forEach(tag -> {
                assertThat(tag.getValue()).doesNotContain("secretQuery");
                assertThat(tag.getValue()).doesNotContain("does-not-exist");
                assertThat(tag.getValue()).doesNotContain(suppliedCorrelationId);
            });
        });
    }

    @Test
    void routeAndQueryValuesDoNotCreateRawValueMetricSeries() throws Exception {
        String firstMissing = "/missing-observability-alpha";
        String secondMissing = "/missing-observability-beta";
        mockMvc.perform(get("/api/sessions/10001?firstQuery=private-a")).andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/api/sessions/20002?secondQuery=private-b")).andExpect(status().is3xxRedirection());
        mockMvc.perform(get(firstMissing + "?raw=alpha")).andExpect(status().isNotFound());
        mockMvc.perform(get(secondMissing + "?raw=beta")).andExpect(status().isNotFound());

        var httpMeters = meterRegistry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals("http.server.requests"))
                .toList();
        assertThat(httpMeters).isNotEmpty();
        assertThat(httpMeters.stream().map(meter -> meter.getId().getTag("uri")))
                .doesNotContain(firstMissing, secondMissing, "/api/sessions/10001", "/api/sessions/20002");
        assertThat(httpMeters.stream().map(meter -> meter.getId().getTag("uri")))
                .allSatisfy(uri -> assertThat(uri).doesNotContain("?", "firstQuery", "secondQuery", "raw"));
    }

    @Test
    void observabilityCannotChangeBusinessStatusAndTagsRemainPrivate() throws Exception {
        mockMvc.perform(get("/profile?instrumentationProbe=private-value"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().exists("X-Correlation-ID"));
        mockMvc.perform(get("/definitely-unmatched-instrumentation-probe?secret=private-value"))
                .andExpect(status().isNotFound())
                .andExpect(header().exists("X-Correlation-ID"));

        meterRegistry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals("http.server.requests"))
                .flatMap(meter -> meter.getId().getTags().stream())
                .forEach(tag -> {
                    assertThat(tag.getKey()).doesNotContainIgnoringCase("principal", "session", "correlation", "user");
                    assertThat(tag.getValue()).doesNotContain("instrumentationProbe", "private-value", "secret");
        });
    }

    @Test
    void realSpringMvcAsyncLifecycleReusesCorrelationAndCleansMdc() throws Exception {
        lifecycleProbeController.reset();
        MvcResult initial = mockMvc.perform(get("/__observability/async")
                        .with(SecurityMockMvcRequestPostProcessors.user("test-user")))
                .andExpect(request().asyncStarted())
                .andReturn();
        String initialId = initial.getResponse().getHeader("X-Correlation-ID");

        MvcResult completed = mockMvc.perform(asyncDispatch(initial)).andReturn();

        assertThat(completed.getResponse().getStatus()).isEqualTo(200);
        assertThat(completed.getResponse().getHeader("X-Correlation-ID")).isEqualTo(initialId);
        assertThat(completed.getResponse().getContentAsString()).isEqualTo("async-ok");
        assertThat(lifecycleProbeController.asyncInvocationCount()).isEqualTo(1);
        assertThat(initial.getRequest().getAttribute(LifecycleProbeController.ASYNC_MDC_ATTRIBUTE))
                .isEqualTo(initialId);
        assertThat(initial.getRequest().getAttribute(LifecycleProbeController.ASYNC_WORKER_MDC_ATTRIBUTE))
                .isNull();
        assertThat(initial.getRequest().getAttribute(LifecycleProbeController.ASYNC_WORKER_AFTER_MDC_ATTRIBUTE))
                .isNull();
        assertThat(initial.getRequest().getAttribute(LifecycleProbeController.ASYNC_DISPATCH_COUNT_ATTRIBUTE))
                .isEqualTo(1);

        MvcResult secondInitial = mockMvc.perform(get("/__observability/async")
                        .with(SecurityMockMvcRequestPostProcessors.user("test-user")))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult secondCompleted = mockMvc.perform(asyncDispatch(secondInitial)).andReturn();
        assertThat(secondCompleted.getResponse().getStatus()).isEqualTo(200);
        assertThat(secondCompleted.getResponse().getHeader("X-Correlation-ID"))
                .isNotEqualTo(initialId);
        assertThat(lifecycleProbeController.asyncInvocationCount()).isEqualTo(2);
    }

    @Test
    void realSpringMvcErrorLifecyclePreservesResponseAndCleansMdc() throws Exception {
        lifecycleProbeController.reset();
        MvcResult result = mockMvc.perform(get("/__observability/error")
                        .with(SecurityMockMvcRequestPostProcessors.user("test-user")))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Correlation-ID"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEqualTo("error-mapped");
        assertThat(result.getRequest().getDispatcherType()).isEqualTo(DispatcherType.REQUEST);
        assertThat(result.getRequest().getAttribute(LifecycleProbeController.ERROR_MDC_ATTRIBUTE))
                .isEqualTo(result.getResponse().getHeader("X-Correlation-ID"));
    }

    @Test
    void frameworkOwnedObservationHandlerFailureIsNotAnApplicationBoundary() {
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return true;
            }

            @Override
            public void onStart(Observation.Context context) {
                throw new IllegalStateException("instrumentation failure");
            }
        });
        AtomicInteger businessCalls = new AtomicInteger();
        String[] response = new String[1];

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                Observation.createNotStarted("http.server.requests", registry).observe(() -> {
                    businessCalls.incrementAndGet();
                    response[0] = "business-ok";
                })))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("instrumentation failure");

        assertThat(businessCalls).hasValue(0);
        assertThat(response[0]).isNull();
        assertThat(org.slf4j.MDC.get("correlationId")).isNull();
    }

    @Test
    void applicationOwnsNoCustomHttpInstrumentationBoundary() throws Exception {
        mockMvc.perform(get("/__observability/no-custom-http-instrumentation"))
                .andExpect(status().isNotFound());
        Set<String> httpMeterNames = meterRegistry.getMeters().stream()
                .map(meter -> meter.getId().getName())
                .filter(name -> name.contains("http"))
                .collect(java.util.stream.Collectors.toSet());

        assertThat(httpMeterNames).contains("http.server.requests");
        assertThat(httpMeterNames).filteredOn(name -> !name.equals("http.server.requests")
                        && !name.equals("http.server.requests.active")
                        && !name.equals("spring.security.http.secured.requests")
                        && !name.equals("spring.security.http.secured.requests.active"))
                .isEmpty();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LifecycleProbeConfiguration {
        @Bean
        LifecycleProbeController lifecycleProbeController() {
            return new LifecycleProbeController();
        }

        @Bean
        LifecycleProbeAdvice lifecycleProbeAdvice() {
            return new LifecycleProbeAdvice();
        }

        @Bean
        WebMvcConfigurer lifecycleProbeInterceptor() {
            return new WebMvcConfigurer() {
                @Override
                public void addInterceptors(InterceptorRegistry registry) {
                    registry.addInterceptor(new HandlerInterceptor() {
                        @Override
                        public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                                                  Object handler) {
                            if (request.getDispatcherType() == DispatcherType.ASYNC) {
                                request.setAttribute(LifecycleProbeController.ASYNC_MDC_ATTRIBUTE,
                                        org.slf4j.MDC.get("correlationId"));
                                request.setAttribute(LifecycleProbeController.ASYNC_DISPATCH_COUNT_ATTRIBUTE, 1);
                            }
                            return true;
                        }
                    });
                }
            };
        }
    }

    @RestController
    static class LifecycleProbeController {
        static final String ASYNC_MDC_ATTRIBUTE = "observability.async.mdc";
        static final String ASYNC_WORKER_MDC_ATTRIBUTE = "observability.async.workerMdc";
        static final String ASYNC_WORKER_AFTER_MDC_ATTRIBUTE = "observability.async.workerAfterMdc";
        static final String ASYNC_DISPATCH_COUNT_ATTRIBUTE = "observability.async.dispatchCount";
        static final String ERROR_MDC_ATTRIBUTE = "observability.error.mdc";
        private final AtomicInteger calls = new AtomicInteger();

        @GetMapping("/__observability/async")
        Callable<ResponseEntity<String>> async(HttpServletRequest request) {
            calls.incrementAndGet();
            return () -> {
                try {
                    request.setAttribute(ASYNC_WORKER_MDC_ATTRIBUTE,
                            org.slf4j.MDC.get("correlationId"));
                    return ResponseEntity.ok("async-ok");
                } finally {
                    request.setAttribute(ASYNC_WORKER_AFTER_MDC_ATTRIBUTE,
                            org.slf4j.MDC.get("correlationId"));
                }
            };
        }

        @GetMapping("/__observability/error")
        String error() {
            throw new ProbeException();
        }

        void reset() {
            calls.set(0);
        }

        int asyncInvocationCount() {
            return calls.get();
        }
    }

    static class ProbeException extends RuntimeException {
    }

    @RestControllerAdvice
    static class LifecycleProbeAdvice {
        @ExceptionHandler(ProbeException.class)
        ResponseEntity<String> handle(ProbeException exception, HttpServletRequest request) {
            request.setAttribute(LifecycleProbeController.ERROR_MDC_ATTRIBUTE,
                    org.slf4j.MDC.get("correlationId"));
            return ResponseEntity.badRequest().body("error-mapped");
        }
    }
}
