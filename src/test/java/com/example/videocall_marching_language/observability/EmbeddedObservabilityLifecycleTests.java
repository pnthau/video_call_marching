package com.example.videocall_marching_language.observability;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequestEvent;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import com.example.videocall_marching_language.VideocallMarchingLanguageApplication;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = VideocallMarchingLanguageApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(EmbeddedObservabilityLifecycleTests.HarnessConfiguration.class)
class EmbeddedObservabilityLifecycleTests {

    @LocalServerPort
    private int port;

    @Autowired
    @Qualifier("harnessExecutor")
    private HarnessExecutor harnessExecutor;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        HarnessProbe.clear();
    }

    @Test
    void realErrorDispatchRetainsCorrelationAndCleansMdc() {
        String correlationId = client.get().uri("/__harness/error")
                .exchange((request, response) -> {
                    assertThat(response.getStatusCode().value()).isEqualTo(418);
                    return response.getHeaders().getFirst("X-Correlation-ID");
                });

        HarnessProbe.Record record = HarnessProbe.record(correlationId);
        assertThat(record).isNotNull();
        assertThat(record.dispatchers()).contains(DispatcherType.REQUEST, DispatcherType.ERROR);
        assertThat(record.invocationCount(DispatcherType.REQUEST)).isEqualTo(1);
        assertThat(record.invocationCount(DispatcherType.ERROR)).isEqualTo(1);
        assertThat(record.mdcValues()).containsOnly(correlationId);
        assertThat(record.cleanupMdcValues()).allMatch(value -> value == null);
        assertThat(record.responseHeaders()).containsOnly(correlationId);
        assertThat(record.finalStatus()).isEqualTo(418);

        String nextCorrelationId = client.get().uri("/__harness/success").retrieve()
                .toBodilessEntity().getHeaders().getFirst("X-Correlation-ID");
        assertThat(nextCorrelationId).isNotEqualTo(correlationId);
        assertThat(HarnessProbe.record(nextCorrelationId).mdcValues()).containsOnly(nextCorrelationId);
    }

    @Test
    void realAsyncDispatchUsesSingleThreadWithoutMdcLeakOrDuplicateInvocation() {
        String first = client.get().uri("/__harness/async").retrieve()
                .toBodilessEntity().getHeaders().getFirst("X-Correlation-ID");
        HarnessProbe.Record firstRecord = HarnessProbe.record(first);

        assertThat(firstRecord.dispatchers()).contains(DispatcherType.REQUEST, DispatcherType.ASYNC);
        assertThat(firstRecord.invocationCount(DispatcherType.REQUEST)).isEqualTo(1);
        assertThat(firstRecord.invocationCount(DispatcherType.ASYNC)).isEqualTo(1);
        assertThat(firstRecord.mdcValues()).containsOnly(first);
        assertThat(firstRecord.responseHeaders()).containsOnly(first);
        assertThat(firstRecord.cleanupMdcValues()).allMatch(value -> value == null);

        String second = client.get().uri("/__harness/async").retrieve()
                .toBodilessEntity().getHeaders().getFirst("X-Correlation-ID");
        assertThat(second).isNotEqualTo(first);
        assertThat(HarnessProbe.record(second).dispatchers())
                .containsExactlyInAnyOrder(DispatcherType.REQUEST, DispatcherType.ASYNC);
        assertThat(harnessExecutor.workerMdcValues()).allMatch(value -> value == null);
    }

    @Configuration(proxyBeanMethods = false)
    static class HarnessConfiguration {
        @Bean
        HarnessController harnessController() {
            return new HarnessController();
        }

        @Bean
        HarnessProbeFilter harnessProbeFilter() {
            return new HarnessProbeFilter();
        }

        @Bean
        HarnessRequestListener harnessRequestListener() {
            return new HarnessRequestListener();
        }

        @Bean
        ServletListenerRegistrationBean<HarnessRequestListener> harnessRequestListenerRegistration(
                HarnessRequestListener listener) {
            return new ServletListenerRegistrationBean<>(listener);
        }

        @Bean
        FilterRegistrationBean<HarnessProbeFilter> harnessProbeFilterRegistration(HarnessProbeFilter filter) {
            FilterRegistrationBean<HarnessProbeFilter> registration = new FilterRegistrationBean<>(filter);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
            registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
            return registration;
        }


        @Bean(destroyMethod = "shutdown")
        HarnessExecutor harnessExecutor() {
            return new HarnessExecutor();
        }

        @Bean
        WebMvcConfigurer harnessAsyncConfigurer(@Qualifier("harnessExecutor") HarnessExecutor executor) {
            return new WebMvcConfigurer() {
                @Override
                public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
                    configurer.setTaskExecutor(executor);
                }
            };
        }
    }

    @RestController
    @RequestMapping("/__harness")
    static class HarnessController {
        @GetMapping("/error")
        void error(HttpServletResponse response) throws IOException {
            response.sendError(418, "embedded harness error");
        }

        @GetMapping("/success")
        ResponseEntity<Void> success() {
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/async")
        java.util.concurrent.Callable<ResponseEntity<Void>> async() {
            return () -> ResponseEntity.noContent().build();
        }
    }

    @Order(Ordered.HIGHEST_PRECEDENCE + 2)
    static class HarnessProbeFilter extends org.springframework.web.filter.OncePerRequestFilter {
        @Override
        protected boolean shouldNotFilterAsyncDispatch() {
            return false;
        }

        @Override
        protected boolean shouldNotFilterErrorDispatch() {
            return false;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            String id = response.getHeader("X-Correlation-ID");
            if (id != null) {
                request.setAttribute(HarnessProbe.REQUEST_ID_ATTRIBUTE, id);
                HarnessProbe.observe(id, request.getDispatcherType(), org.slf4j.MDC.get("correlationId"),
                        response.getHeader("X-Correlation-ID"), response.getStatus());
            }
            try {
                chain.doFilter(request, response);
            } finally {
            }
        }
    }

    static class HarnessRequestListener implements ServletRequestListener {
        @Override
        public void requestDestroyed(ServletRequestEvent event) {
            Object id = event.getServletRequest().getAttribute(HarnessProbe.REQUEST_ID_ATTRIBUTE);
            if (id instanceof String correlationId) {
                HarnessProbe.cleanup(correlationId, org.slf4j.MDC.get("correlationId"));
            }
        }
    }

    static final class HarnessProbe {
        static final String REQUEST_ID_ATTRIBUTE = HarnessProbe.class.getName() + ".requestId";
        private static final Map<String, Record> RECORDS = new ConcurrentHashMap<>();

        static void clear() {
            RECORDS.clear();
        }

        static void observe(String id, DispatcherType dispatcher, String mdc, String header, int status) {
            RECORDS.computeIfAbsent(id, ignored -> new Record()).observe(dispatcher, mdc, header, status);
        }

        static void cleanup(String id, String mdc) {
            RECORDS.computeIfAbsent(id, ignored -> new Record()).cleanup(mdc);
        }

        static Record record(String id) {
            return RECORDS.get(id);
        }

        static final class Record {
            private final List<DispatcherType> dispatchers = new ArrayList<>();
            private final List<String> mdcValues = new ArrayList<>();
            private final List<String> cleanupMdcValues = new ArrayList<>();
            private final List<String> responseHeaders = new ArrayList<>();
            private final List<Integer> statuses = new ArrayList<>();

            synchronized void observe(DispatcherType dispatcher, String mdc, String header, int status) {
                dispatchers.add(dispatcher);
                mdcValues.add(mdc);
                responseHeaders.add(header);
                statuses.add(status);
            }

            synchronized void cleanup(String mdc) {
                cleanupMdcValues.add(mdc);
            }

            synchronized List<DispatcherType> dispatchers() { return new ArrayList<>(dispatchers); }
            synchronized List<String> mdcValues() { return new ArrayList<>(mdcValues); }
            synchronized List<String> cleanupMdcValues() { return new ArrayList<>(cleanupMdcValues); }
            synchronized List<String> responseHeaders() { return new ArrayList<>(responseHeaders); }
            synchronized int invocationCount(DispatcherType type) {
                return (int) dispatchers.stream().filter(type::equals).count();
            }
            synchronized int finalStatus() { return statuses.get(statuses.size() - 1); }
        }
    }

    static final class HarnessExecutor extends ThreadPoolTaskExecutor {
        private final List<String> workerMdcValues = new ArrayList<>();

        HarnessExecutor() {
            setCorePoolSize(1);
            setMaxPoolSize(1);
            setQueueCapacity(0);
            setThreadNamePrefix("observability-harness-");
            initialize();
        }

        @Override
        public void execute(Runnable command) {
            super.execute(() -> {
                synchronized (workerMdcValues) {
                    workerMdcValues.add(org.slf4j.MDC.get("correlationId"));
                }
                command.run();
                synchronized (workerMdcValues) {
                    workerMdcValues.add(org.slf4j.MDC.get("correlationId"));
                }
            });
        }

        List<String> workerMdcValues() {
            synchronized (workerMdcValues) {
                return List.copyOf(workerMdcValues);
            }
        }

    }
}
