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
        final HttpRequest request = buildRequest(url, body, contentType, charset);
        try {
            final java.net.http.HttpResponse<String> response = send(request);
            return new HttpResponse(response.statusCode(), response.body());
        } catch (final InterruptedException e) {
            // Whoever interrupted this thread wants it to stop, so pass the interruption on rather than swallowing it
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while posting to " + describe(request.uri()), e);
        }
    }

    /**
     * Turns the parameters of a request into a request. The platform refuses to build one for an address that is not
     * a valid {@code http(s)} URL, or for a content type that is not a valid header value, and it does so with
     * unchecked exceptions; since both come from the caller, and neither leaves any request made, they are reported
     * as the failure to make a request that this API promises.
     *
     * @param url the address to post to
     * @param body the body to send
     * @param contentType the media type of the body
     * @param charset the charset to encode the body with
     * @return the request to send
     * @throws IOException if the address or the content type cannot be turned into a request
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
            // Deliberately not chained: both URI.create and the request builder quote the address they rejected,
            // and the address can itself be a secret, as explained on describe(URI)
            throw new IOException("Cannot post to the given address: " + explain(e));
        }
    }

    /**
     * Says why an address or a content type could not be turned into a request, without repeating either of them.
     *
     * @param failure why the platform refused to build the request
     * @return an explanation safe to log
     */
    private static String explain(final IllegalArgumentException failure)
    {
        // A malformed address is rejected with a syntax error that says only what is wrong and where, unlike the
        // message of the exception carrying it, so that much can be passed on
        if (failure.getCause() instanceof URISyntaxException syntaxError) {
            return syntaxError.getReason() + " at index " + syntaxError.getIndex();
        }
        return "it must be a valid http(s) URL, and the content type a valid header value";
    }

    /**
     * Describes where a request was going, in a form that is safe to log. An address can itself be a secret — a chat
     * webhook carries its authorization token in its path, and a URL can even carry credentials in its user
     * information — so only the service being reached is ever named, never the rest of the address.
     *
     * @param target the address the request was made to
     * @return the scheme, host and port of the address
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
