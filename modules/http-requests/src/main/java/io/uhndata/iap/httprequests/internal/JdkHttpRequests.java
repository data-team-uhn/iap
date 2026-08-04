/*
 * Copyright 2026 DATA @ UHN. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.uhndata.iap.httprequests.internal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.httprequests.api.HttpRequests;
import io.uhndata.iap.httprequests.api.HttpResponse;

/**
 * Default implementation of {@link HttpRequests}, built on the HTTP client the Java platform provides. Using it
 * instead of an HTTP library keeps this module dependency-free, and gives every request a timeout for free.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = HttpRequests.class)
public class JdkHttpRequests implements HttpRequests
{
    /** How long to wait for a service to accept a connection. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** How long to wait for a service to answer, once it accepted the connection. */
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    @Override
    public HttpResponse post(final String url, final String body, final String contentType) throws IOException
    {
        return post(url, body, contentType, StandardCharsets.UTF_8);
    }

    @Override
    public HttpResponse post(final String url, final String body, final String contentType, final Charset charset)
        throws IOException
    {
        final HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(RESPONSE_TIMEOUT)
            .header("Content-Type", contentType + "; charset=" + charset.name())
            .POST(BodyPublishers.ofString(body, charset))
            .build();
        try {
            final java.net.http.HttpResponse<String> response = send(request);
            return new HttpResponse(response.statusCode(), response.body());
        } catch (final InterruptedException e) {
            // Whoever interrupted this thread wants it to stop, so pass the interruption on rather than swallowing it
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while posting to " + url, e);
        }
    }

    /**
     * Actually sends the request. Overridable so that a test can stand in for a remote service that misbehaves in
     * ways a real one cannot be made to on demand.
     *
     * @param request the request to send
     * @return the raw response
     * @throws IOException if the request could not be made
     * @throws InterruptedException if the calling thread was interrupted while waiting for the response
     */
    protected java.net.http.HttpResponse<String> send(final HttpRequest request)
        throws IOException, InterruptedException
    {
        return this.client.send(request, BodyHandlers.ofString());
    }
}
