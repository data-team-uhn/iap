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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.httprequests.api.HttpRequests;
import io.uhndata.iap.httprequests.api.HttpResponse;
import io.uhndata.iap.httprequests.api.Multipart;

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

    private static final String LINE_BREAK = "\r\n";

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
        return exchange(buildRequest(url, Map.of(), contentType + "; charset=" + charset.name(),
            BodyPublishers.ofString(body, charset)));
    }

    @Override
    public HttpResponse post(final String url, final Map<String, String> headers, final Multipart body)
        throws IOException
    {
        // When POSTing a multipart body, split the parts on a random string that should not occur inside any part
        final String boundary = "iap-" + UUID.randomUUID();
        return exchange(buildRequest(url, headers, "multipart/form-data; boundary=" + boundary,
            encode(body, boundary)));
    }

    /**
     * Sends a request and waits for the answer.
     *
     * @param request the request to send
     * @return what the service answered
     * @throws IOException if the request could not be made
     */
    private HttpResponse exchange(final HttpRequest request) throws IOException
    {
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
     * a valid {@code http(s)} URL, or for a header that is not a valid header or is one it sets itself, and it does
     * so with unchecked exceptions; since these come from the caller, and none leaves any request made, they are
     * reported as the failure to make a request that this API promises.
     *
     * @param url the address to post to
     * @param headers the caller's headers
     * @param contentType the full {@code Content-Type} of the body, which replaces any the caller gave
     * @param body the body to send
     * @return the request to send
     * @throws IOException if the address or a header cannot be turned into a request
     */
    private HttpRequest buildRequest(final String url, final Map<String, String> headers, final String contentType,
        final BodyPublisher body) throws IOException
    {
        try {
            final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(RESPONSE_TIMEOUT);
            headers.forEach(request::header);
            return request.setHeader("Content-Type", contentType).POST(body).build();
        } catch (final IllegalArgumentException e) {
            // Deliberately not chained: both URI.create and the request builder quote what they rejected, and both
            // an address and a header can be secrets, as explained on describe(URI)
            throw new IOException("Cannot post to the given address: " + explain(e));
        }
    }

    /**
     * Lays the parts out as a {@code multipart/form-data} body. Opens each part's content only when the client
     * gets to it.
     *
     * @param body the parts
     * @param boundary what separates the parts, which must not occur in any of them
     * @return the body to send
     */
    private static BodyPublisher encode(final Multipart body, final String boundary)
    {
        // Note: There's no Java builtin for creating multipart bodies, and the libraries that have it (e.g.
        // Apache HttpClient 4) introduce a dependency, which we are avoiding in this module.
        // Thus, we implement a multipart body encoder here.
        final List<BodyPublisher> pieces = new ArrayList<>();
        for (final Multipart.Part part : body.getParts()) {
            final StringBuilder head = new StringBuilder("--").append(boundary).append(LINE_BREAK)
                .append("Content-Disposition: form-data; name=\"").append(quote(part.name())).append('"');
            final String filename = part.filename();
            if (filename != null) {
                head.append("; filename=\"").append(quote(filename)).append('"');
            }
            head.append(LINE_BREAK).append("Content-Type: ").append(part.contentType())
                .append(LINE_BREAK).append(LINE_BREAK);
            pieces.add(BodyPublishers.ofString(head.toString(), StandardCharsets.UTF_8));
            pieces.add(BodyPublishers.ofInputStream(() -> open(part.content())));
            pieces.add(BodyPublishers.ofString(LINE_BREAK));
        }
        pieces.add(BodyPublishers.ofString("--" + boundary + "--" + LINE_BREAK));
        return BodyPublishers.concat(pieces.toArray(BodyPublisher[]::new));
    }

    /**
     * Makes a name safe to quote in a part's headers, the same way browsers do.
     *
     * @param name a field or file name
     * @return the name, with quotes and line break characters percent-encoded
     */
    private static String quote(final String name)
    {
        // Quotes would end a name in quotes early, and a line break would start a new header line
        return name.replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A");
    }

    /**
     * Opens a part's content for the client, which can only be handed unchecked exceptions; it reports them as a
     * failure to send the request.
     *
     * @param content the part's content
     * @return the open content
     */
    private static InputStream open(final Multipart.Content content)
    {
        try {
            return content.open();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Obtain the reason why an address or a header could not be turned into a request.
     *
     * @param failure why the platform refused to build the request
     * @return an explanation safe to log
     */
    private static String explain(final IllegalArgumentException failure)
    {
        // A malformed address is rejected with a syntax error that says only what is wrong and where, unlike the
        // message of the exception carrying it, so that much can be passed on
        if (failure.getCause() instanceof final URISyntaxException syntaxError) {
            return syntaxError.getReason() + " at index " + syntaxError.getIndex();
        }
        return "it must be a valid http(s) URL, and every header a valid one that may be set";
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
