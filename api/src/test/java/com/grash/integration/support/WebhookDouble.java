package com.grash.integration.support;

import com.grash.service.WebhookDispatchService;
import org.mockito.Mockito;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.net.URI;

/**
 * Webhook receiver double: takes the place of the HTTP client of {@link WebhookDispatchService}, so the real
 * dispatch (endpoint filters, signature, asynchronous thread, error handling) runs and nothing leaves the JVM.
 * <p>
 * Each request is recorded under its event ({@code X-Webhook-Event}) with the arguments {@code [url, body]} and is
 * answered with 200, unless a failure is armed with {@link #failOn(String, RuntimeException)}. Dispatch is
 * asynchronous: wait with {@link #awaitCalls}.
 */
public class WebhookDouble extends CallRecorder implements ClientHttpRequestFactory {

    /**
     * A public address that passes {@code WebhookUrlValidator} without DNS. It is never contacted.
     */
    public static final String ENDPOINT_URL = "https://192.88.99.1/p00-webhook";

    private volatile boolean closed;

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) {
        if (closed) {
            throw new IllegalStateException("webhook to " + uri + " after the end of its test");
        }
        return new MockClientHttpRequest(httpMethod, uri) {
            @Override
            protected ClientHttpResponse executeInternal() {
                record(getHeaders().getFirst("X-Webhook-Event"), uri, getBodyAsString());
                return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
            }
        };
    }

    /**
     * Routes the requests of {@code service} to this double. A {@code @MockBean} service is left alone.
     */
    public void bindTo(WebhookDispatchService service) {
        Object target = AopTestUtils.getUltimateTargetObject(service);
        if (Mockito.mockingDetails(target).isMock()) {
            return;
        }
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(target, "restTemplate");
        restTemplate.setRequestFactory(this);
    }

    /**
     * Rejects any later request. The real HTTP client is not restored: tests never send webhooks.
     */
    public void unbind() {
        closed = true;
    }
}
