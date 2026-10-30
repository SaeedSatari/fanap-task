package com.network.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.network.common.ApiException;
import com.network.config.NetworkProperties;
import com.network.ping.PingOperationResponse;
import com.network.ping.PingStatus;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/** Verifies how the RestTemplate client behaves on timeout, HTTP errors and invalid responses. */
class PingServiceClientTest {

    private static final String BASE = "http://ping.test";
    private static final String SUBMIT_URL = BASE + "/internal/ping/devices/1/pings";
    private static final UUID OP_ID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");

    private static final String OPERATION_JSON = """
            {"id":"7c9e6679-7425-40de-944b-e07fc1f90ae7","deviceId":1,"status":"PENDING",
             "reachable":null,"responseTimeMs":null,"attempts":0,"maxAttempts":3,"errorMessage":null,
             "createdAt":"2026-01-01T00:00:00Z","startedAt":null,"finishedAt":null,"durationMs":null}""";

    private MockRestServiceServer server;
    private PingServiceClient client;

    @BeforeEach
    void setUp() {
        NetworkProperties props = new NetworkProperties();
        props.getClients().getPing().setBaseUrl(BASE);
        props.getClients().setRetries(1);
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new PingServiceClient(restTemplate, new DownstreamCaller(props, new ObjectMapper()),
                new ServiceEndpoints(props, new MockEnvironment()));
    }

    private static DownstreamException downstream(Throwable t) {
        assertThat(t).isInstanceOf(DownstreamException.class);
        return (DownstreamException) t;
    }


    @Test
    void requestPing_accepted_forwardsIdempotencyKey() {
        server.expect(once(), requestTo(SUBMIT_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "abc-1"))
                .andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body(OPERATION_JSON));

        PingSubmission submission = client.requestPing(1L, "abc-1");

        assertThat(submission.created()).isTrue();
        assertThat(submission.operation().id()).isEqualTo(OP_ID);
        assertThat(submission.operation().status()).isEqualTo(PingStatus.PENDING);
        server.verify();
    }

    @Test
    void requestPing_replayedWith200_isNotReportedAsCreated() {
        server.expect(requestTo(SUBMIT_URL))
                .andRespond(withSuccess(OPERATION_JSON, MediaType.APPLICATION_JSON));

        assertThat(client.requestPing(1L, "abc-1").created()).isFalse();
    }


    @Test
    void timeout_onPost_mapsTo504_andIsNotRetried() {
        server.expect(once(), requestTo(SUBMIT_URL))
                .andRespond(request -> { throw new SocketTimeoutException("Read timed out"); });

        assertThatThrownBy(() -> client.requestPing(1L, null)).satisfies(t -> {
            DownstreamException e = downstream(t);
            assertThat(e.getStatus().value()).isEqualTo(504);
            assertThat(e.getCode()).isEqualTo("DOWNSTREAM_TIMEOUT");
        });
        server.verify();
    }

    @Test
    void timeout_onGet_isRetriedOnce_thenMapsTo504() {
        server.expect(times(2), requestTo(BASE + "/internal/ping/pings/" + OP_ID))
                .andRespond(request -> { throw new SocketTimeoutException("Read timed out"); });

        assertThatThrownBy(() -> client.getOperation(OP_ID)).satisfies(t ->
                assertThat(downstream(t).getCode()).isEqualTo("DOWNSTREAM_TIMEOUT"));
        server.verify();
    }

    @Test
    void connectionRefused_mapsTo503() {
        server.expect(once(), requestTo(SUBMIT_URL))
                .andRespond(request -> { throw new ConnectException("Connection refused"); });

        assertThatThrownBy(() -> client.requestPing(1L, null)).satisfies(t -> {
            DownstreamException e = downstream(t);
            assertThat(e.getStatus().value()).isEqualTo(503);
            assertThat(e.getCode()).isEqualTo("DOWNSTREAM_UNAVAILABLE");
        });
    }


    @Test
    void serverError_onPost_mapsTo502() {
        server.expect(once(), requestTo(SUBMIT_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.requestPing(1L, null)).satisfies(t -> {
            DownstreamException e = downstream(t);
            assertThat(e.getStatus().value()).isEqualTo(502);
            assertThat(e.getCode()).isEqualTo("DOWNSTREAM_ERROR");
        });
        server.verify();
    }

    @Test
    void serverError_onGet_isRetried_andRecoversOnSecondAttempt() {
        String url = BASE + "/internal/ping/pings/" + OP_ID;
        server.expect(once(), requestTo(url)).andRespond(withServerError());
        server.expect(once(), requestTo(url)).andRespond(withSuccess(OPERATION_JSON, MediaType.APPLICATION_JSON));

        assertThat(client.getOperation(OP_ID).id()).isEqualTo(OP_ID);
        server.verify();
    }

    @Test
    void clientError_isPassedThroughWithDownstreamStatusAndCode() {
        server.expect(requestTo(SUBMIT_URL)).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"status\":404,\"code\":\"DEVICE_NOT_FOUND\",\"message\":\"Device 1 not found\"}"));

        assertThatThrownBy(() -> client.requestPing(1L, null)).satisfies(t -> {
            assertThat(t).isNotInstanceOf(DownstreamException.class);
            ApiException e = (ApiException) t;
            assertThat(e.getStatus().value()).isEqualTo(404);
            assertThat(e.getCode()).isEqualTo("DEVICE_NOT_FOUND");
            assertThat(e.getMessage()).isEqualTo("Device 1 not found");
        });
    }

    @Test
    void clientError_withNonJsonBody_keepsStatusWithGenericCode() {
        server.expect(requestTo(SUBMIT_URL)).andRespond(withStatus(HttpStatus.CONFLICT)
                .contentType(MediaType.TEXT_PLAIN).body("nope"));

        assertThatThrownBy(() -> client.requestPing(1L, null)).satisfies(t -> {
            ApiException e = (ApiException) t;
            assertThat(e.getStatus().value()).isEqualTo(409);
            assertThat(e.getCode()).isEqualTo("DOWNSTREAM_REJECTED");
        });
    }


    @Test
    void malformedJson_mapsToInvalidResponse() {
        server.expect(requestTo(SUBMIT_URL))
                .andRespond(withSuccess("{not json", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.requestPing(1L, null)).satisfies(t -> {
            DownstreamException e = downstream(t);
            assertThat(e.getStatus().value()).isEqualTo(502);
            assertThat(e.getCode()).isEqualTo("INVALID_DOWNSTREAM_RESPONSE");
        });
    }

    @Test
    void unexpectedContentType_mapsToInvalidResponse() {
        server.expect(requestTo(SUBMIT_URL))
                .andRespond(withSuccess("<html>oops</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> client.requestPing(1L, null)).satisfies(t ->
                assertThat(downstream(t).getCode()).isEqualTo("INVALID_DOWNSTREAM_RESPONSE"));
    }

    @Test
    void validJsonMissingRequiredFields_mapsToInvalidResponse() {
        server.expect(requestTo(SUBMIT_URL))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.requestPing(1L, null)).satisfies(t ->
                assertThat(downstream(t).getCode()).isEqualTo("INVALID_DOWNSTREAM_RESPONSE"));
    }

    @Test
    void emptyBody_mapsToInvalidResponse() {
        server.expect(requestTo(SUBMIT_URL)).andRespond(withStatus(HttpStatus.OK));

        assertThatThrownBy(() -> client.requestPing(1L, null)).satisfies(t ->
                assertThat(downstream(t).getCode()).isEqualTo("INVALID_DOWNSTREAM_RESPONSE"));
    }

    @Test
    void invalidResponse_isNeverRetried() {
        server.expect(once(), requestTo(BASE + "/internal/ping/pings/" + OP_ID))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getOperation(OP_ID)).isInstanceOf(DownstreamException.class);
        server.verify();
    }

    @Test
    void history_withOneMalformedElement_isRejected() {
        server.expect(requestTo(BASE + "/internal/ping/devices/1/pings?limit=5"))
                .andRespond(withSuccess("[" + OPERATION_JSON + ",{}]", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.history(1L, 5)).satisfies(t ->
                assertThat(downstream(t).getCode()).isEqualTo("INVALID_DOWNSTREAM_RESPONSE"));
    }

    @Test
    void history_parsesValidList() {
        server.expect(requestTo(BASE + "/internal/ping/devices/1/pings?limit=5"))
                .andRespond(withSuccess("[" + OPERATION_JSON + "]", MediaType.APPLICATION_JSON));

        assertThat(client.history(1L, 5)).extracting(PingOperationResponse::id).containsExactly(OP_ID);
    }
}
