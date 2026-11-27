package com.grash.integration.support;

import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;

import java.io.IOException;
import java.net.URI;

/**
 * Sends every request of {@code delegate} to a port of the loopback address, keeping its path: a webhook endpoint
 * keeps an address that passes {@code WebhookUrlValidator}, and the real HTTP client, with its time limits, talks to
 * a local {@link SilentServer}. Nothing leaves the machine.
 */
public final class LoopbackRequestFactory implements ClientHttpRequestFactory {

    private final ClientHttpRequestFactory delegate;
    private final int port;

    public LoopbackRequestFactory(ClientHttpRequestFactory delegate, int port) {
        this.delegate = delegate;
        this.port = port;
    }

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
        return delegate.createRequest(URI.create("http://127.0.0.1:" + port + uri.getRawPath()), httpMethod);
    }
}
