package com.example.videocall_marching_language.config.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.slf4j.MDC;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationIdFilterTests {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void serverGeneratesIdAndDoesNotReflectIncomingHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "client-controlled-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) ->
                assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo(
                        ((HttpServletResponse) res).getHeader(CorrelationIdFilter.HEADER_NAME)));

        String generated = response.getHeader(CorrelationIdFilter.HEADER_NAME);
        assertThat(generated).matches("[A-Za-z0-9._-]{1,64}");
        assertThat(generated).isNotEqualTo("client-controlled-id");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void effectiveIdIsReusedForAsyncDispatchAndMdcIsCleanedEachTime() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(request, firstResponse, (req, res) ->
                assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNotNull());

        MockHttpServletResponse asyncResponse = new MockHttpServletResponse();
        filter.doFilter(request, asyncResponse, (req, res) ->
                assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo(
                        firstResponse.getHeader(CorrelationIdFilter.HEADER_NAME)));

        assertThat(asyncResponse.getHeader(CorrelationIdFilter.HEADER_NAME))
                .isEqualTo(firstResponse.getHeader(CorrelationIdFilter.HEADER_NAME));
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void mdcIsCleanedWhenApplicationChainFails() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain failingChain = (req, res) -> {
            throw new ServletException("application failure");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, failingChain))
                .isInstanceOf(ServletException.class);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void errorRedispatchReusesIdAndRestoresOuterMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MDC.put(CorrelationIdFilter.MDC_KEY, "outer-context");
        String[] observed = new String[1];

        filter.doFilter(request, response, (req, res) -> {
            observed[0] = MDC.get(CorrelationIdFilter.MDC_KEY);
            request.setDispatcherType(DispatcherType.ERROR);
            filter.doFilter(request, response, (errorReq, errorRes) -> {
                assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo(observed[0]);
                assertThat(((HttpServletResponse) errorRes).getHeader(CorrelationIdFilter.HEADER_NAME))
                        .isEqualTo(observed[0]);
            });
            assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo(observed[0]);
        });

        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isEqualTo(observed[0]);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("outer-context");
    }

    @Test
    void topLevelErrorRedispatchHasMdcAndCleansUpForNextRequest() throws Exception {
        MockHttpServletRequest errorRequest = new MockHttpServletRequest();
        errorRequest.setDispatcherType(DispatcherType.ERROR);
        MockHttpServletResponse errorResponse = new MockHttpServletResponse();
        filter.doFilter(errorRequest, errorResponse, (req, res) ->
                assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo(
                        errorResponse.getHeader(CorrelationIdFilter.HEADER_NAME)));
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();

        MockHttpServletRequest nextRequest = new MockHttpServletRequest();
        MockHttpServletResponse nextResponse = new MockHttpServletResponse();
        filter.doFilter(nextRequest, nextResponse, (req, res) ->
                assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNotEqualTo(
                        errorResponse.getHeader(CorrelationIdFilter.HEADER_NAME)));
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void asyncRedispatchEstablishesMdcAndCleansUp() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse initialResponse = new MockHttpServletResponse();
        filter.doFilter(request, initialResponse, (req, res) -> { });
        String id = initialResponse.getHeader(CorrelationIdFilter.HEADER_NAME);

        request.setDispatcherType(DispatcherType.ASYNC);
        MockHttpServletResponse asyncResponse = new MockHttpServletResponse();
        filter.doFilter(request, asyncResponse, (req, res) ->
                assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo(id));

        assertThat(asyncResponse.getHeader(CorrelationIdFilter.HEADER_NAME)).isEqualTo(id);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void cleanupIsDeterministicForSuccessThenErrorAndErrorThenSuccess() throws Exception {
        MockHttpServletRequest success = new MockHttpServletRequest();
        filter.doFilter(success, new MockHttpServletResponse(), (req, res) -> { });
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();

        MockHttpServletRequest error = new MockHttpServletRequest();
        error.setDispatcherType(DispatcherType.ERROR);
        assertThatThrownBy(() -> filter.doFilter(error, new MockHttpServletResponse(),
                (req, res) -> { throw new IOException("expected"); }))
                .isInstanceOf(IOException.class);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();

        MockHttpServletRequest anotherError = new MockHttpServletRequest();
        anotherError.setDispatcherType(DispatcherType.ERROR);
        filter.doFilter(anotherError, new MockHttpServletResponse(), (req, res) -> { });
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), (req, res) -> { });
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
