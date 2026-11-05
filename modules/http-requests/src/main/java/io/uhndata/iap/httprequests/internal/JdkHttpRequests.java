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
import java.net.URISyntaxException;
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
 * Sends requests through the JDK's {@link HttpClient}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = HttpRequests.class)
public class JdkHttpRequests implements HttpRequests
{
    /** How long to wait for a service to accept the connection. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * How long to wait for the status and headers of the response, counted from sending, connecting included. The
     * JDK's {@code HttpClient} does not apply it to the body.
     */
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
        final HttpRequest request = buildRequest(url, body, contentType, charset);
        try {
            final java.net.http.HttpResponse<String> response = send(request);
            return new HttpResponse(response.statusCode(), response.body());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while posting to " + describe(request.uri()), e);
        }
    }

    /**
     * Builds the POST request. The JDK refuses an address that is not a valid {@code http(s)} URL, and a content type
     * that is not a valid header value, with an {@link IllegalArgumentException}. This turns it into an
     * {@link IOException}, as for any other request that could not be made.
     *
     * @param url the address to post to
     * @param body the body to send
     * @param contentType the media type of the body
     * @param charset the charset to encode the body with
     * @return the request, not yet sent
     * @throws IOException if the JDK refuses the address or the content type
     */
    private HttpRequest buildRequest(final String url, final String body, final String contentType,
        final Charset charset) throws IOException
    {
        try {
            return HttpRequest.newBuilder(URI.create(url))
                .timeout(RESPONSE_TIMEOUT)
                .header("Content-Type", contentType + "; charset=" + charset.name())
                .POST(BodyPublishers.ofString(body, charset))
                .build();
        } catch (final IllegalArgumentException e) {
            // Not chained: the JDK's message can quote the address, and an address can be a secret
            throw new IOException("Cannot post to the given address: " + explain(e));
        }
    }

    /**
     * Says why the JDK refused to build the request, without quoting the address or the content type.
     *
     * @param failure the JDK's refusal
     * @return the reason, safe to log
     */
    private static String explain(final IllegalArgumentException failure)
    {
        // getReason() leaves out the address that getMessage() quotes
        if (failure.getCause() instanceof final URISyntaxException syntaxError) {
            return syntaxError.getReason() + " at index " + syntaxError.getIndex();
        }
        return "it must be a valid http(s) URL, and the content type a valid header value";
    }

    /**
     * Names the service a request was going to, in a form safe to log. It takes the host and not the authority,
     * since the authority can hold a username and password.
     *
     * @param target the address of the request
     * @return the scheme and host, and the port if the address names one
     */
    private static String describe(final URI target)
    {
        final StringBuilder description = new StringBuilder(target.getScheme()).append("://").append(target.getHost());
        if (target.getPort() != -1) {
            description.append(':').append(target.getPort());
        }
        return description.toString();
    }

    /**
     * Sends the request and waits for the response. Protected so that a test can simulate an interruption.
     *
     * @param request the request to send
     * @return the response, with its body as text
     * @throws IOException if the request could not be made or got no answer in time
     * @throws InterruptedException if the calling thread was interrupted while waiting for the response
     */
    protected java.net.http.HttpResponse<String> send(final HttpRequest request)
        throws IOException, InterruptedException
    {
        return this.client.send(request, BodyHandlers.ofString());
    }
}
