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
import java.net.InetSocketAddress;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.uhndata.iap.httprequests.api.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link JdkHttpRequests}, driven against a real HTTP server listening on a local port.
 *
 * @version $Id$
 * @since 0.1.0
 */
class JdkHttpRequestsTest
{
    /** Stands in for the token part of a webhook address, so that a leak into a message is recognizable. */
    private static final String SECRET = "SUPER-SECRET-TOKEN";

    /** What the stand-in service answers with, and what it was asked. */
    private static final class Recorder
    {
        private int status = 200;

        private String answer = "ok";

        private final List<String> bodies = new ArrayList<>();

        private final List<String> contentTypes = new ArrayList<>();
    }

    private final Recorder recorder = new Recorder();

    private final JdkHttpRequests requests = new JdkHttpRequests();

    private HttpServer server;

    private String url;

    @BeforeEach
    void startTheService() throws IOException
    {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/hook", this::answer);
        this.server.start();
        this.url = "http://127.0.0.1:" + this.server.getAddress().getPort() + "/hook";
    }

    @AfterEach
    void stopTheService()
    {
        this.server.stop(0);
    }

    @Test
    void postsTheBodyAndReturnsWhatTheServiceAnswered() throws IOException
    {
        final HttpResponse response = this.requests.post(this.url, "{\"a\":1}", "application/json");

        assertEquals(200, response.getStatusCode());
        assertEquals("ok", response.getBody());
        assertTrue(response.isSuccessful());
        assertEquals(List.of("{\"a\":1}"), this.recorder.bodies);
        assertEquals(List.of("application/json; charset=UTF-8"), this.recorder.contentTypes);
    }

    @Test
    void sendsTheBodyInTheRequestedCharset() throws IOException
    {
        final Charset latin1 = Charset.forName("ISO-8859-1");

        this.requests.post(this.url, "café", "text/plain", latin1);

        // The service read the bytes back as ISO-8859-1, so they were written as ISO-8859-1
        assertEquals(List.of("café"), this.recorder.bodies);
        assertEquals(List.of("text/plain; charset=ISO-8859-1"), this.recorder.contentTypes);
    }

    @Test
    void aRefusedRequestIsAnAnswer() throws IOException
    {
        this.recorder.status = 400;
        this.recorder.answer = "invalid_payload";

        final HttpResponse response = this.requests.post(this.url, "nonsense", "application/json");

        // Reaching a service and being told no is not a failure to make the request
        assertEquals(400, response.getStatusCode());
        assertEquals("invalid_payload", response.getBody());
        assertFalse(response.isSuccessful());
    }

    @Test
    void anEmptyAnswerIsAnEmptyBody() throws IOException
    {
        this.recorder.answer = "";

        assertEquals("", this.requests.post(this.url, "{}", "application/json").getBody());
    }

    @Test
    void anUnreachableServiceIsAFailure()
    {
        this.server.stop(0);

        assertThrows(IOException.class, () -> this.requests.post(this.url, "{}", "application/json"));
    }

    @Test
    void anInterruptionIsPassedOnRatherThanSwallowed()
    {
        final IOException failure = assertThrows(IOException.class,
            () -> interruptedRequests().post(this.url, "{}", "application/json"));

        // The service that could not be reached is named, down to the port, since several can be in flight at once
        assertTrue(failure.getMessage().contains("http://127.0.0.1:" + this.server.getAddress().getPort()));
        // The thread stays interrupted, so whoever asked it to stop is not ignored
        assertTrue(Thread.interrupted());
    }

    @Test
    void aFailureNamesTheServiceButNotTheSecretInItsAddress()
    {
        // A chat webhook address carries its authorization token, so an address must never reach a log in full
        final String webhook = "https://hooks.slack.com/services/T00000000/B00000000/" + SECRET;

        final IOException failure = assertThrows(IOException.class,
            () -> interruptedRequests().post(webhook, "{}", "application/json"));

        assertEquals("Interrupted while posting to https://hooks.slack.com", failure.getMessage());
        assertMentionsNoSecret(failure);
        assertTrue(Thread.interrupted());
    }

    @Test
    void aFailureNamesTheServiceButNotTheCredentialsInItsAddress()
    {
        // Credentials in the user information of an address are a secret too, so naming the host is not enough,
        // it has to be the host rather than the whole authority
        final String withCredentials = "https://iap:" + SECRET + "@files.example.com/upload";

        final IOException failure = assertThrows(IOException.class,
            () -> interruptedRequests().post(withCredentials, "{}", "application/json"));

        assertEquals("Interrupted while posting to https://files.example.com", failure.getMessage());
        assertMentionsNoSecret(failure);
        assertTrue(Thread.interrupted());
    }

    @Test
    void aMalformedAddressIsAFailureToMakeTheRequest()
    {
        final String address = "https://hooks.slack.com/services/" + SECRET + " and more";

        final IOException failure =
            assertThrows(IOException.class, () -> this.requests.post(address, "{}", "text/plain"));

        // What is wrong and where can be said without quoting the address it is wrong in: the index is the space
        assertEquals("Cannot post to the given address: Illegal character in path at index " + address.indexOf(' '),
            failure.getMessage());
        assertMentionsNoSecret(failure);
    }

    @Test
    void anAddressThatIsNotHttpIsAFailureToMakeTheRequest()
    {
        final IOException failure = assertThrows(IOException.class,
            () -> this.requests.post("ftp://files.example.com/" + SECRET, "{}", "text/plain"));

        assertEquals("Cannot post to the given address: it must be a valid http(s) URL, and the content type a "
            + "valid header value", failure.getMessage());
        assertMentionsNoSecret(failure);
    }

    @Test
    void aContentTypeThatIsNotAValidHeaderIsAFailureToMakeTheRequest()
    {
        // Refusing the header the platform refuses is also what keeps a content type from injecting one of its own
        final IOException failure = assertThrows(IOException.class,
            () -> this.requests.post(this.url, "{}", "text/plain\r\nX-Injected: yes"));

        assertEquals("Cannot post to the given address: it must be a valid http(s) URL, and the content type a "
            + "valid header value", failure.getMessage());
        assertTrue(this.recorder.bodies.isEmpty());
    }

    /** A stand-in that is interrupted instead of answering, the one failure a real service cannot be made to have. */
    private static JdkHttpRequests interruptedRequests()
    {
        return new JdkHttpRequests()
        {
            @Override
            protected java.net.http.HttpResponse<String> send(final HttpRequest request) throws InterruptedException
            {
                throw new InterruptedException("stop what you are doing");
            }
        };
    }

    /** Checks that neither the failure nor anything it carries repeats the secret part of an address. */
    private void assertMentionsNoSecret(final IOException failure)
    {
        // The cause is checked too: the platform quotes the address it rejected in its own message
        final String reported = failure.getMessage() + " " + failure.getCause();
        assertFalse(reported.contains(SECRET), "The secret reached a message: " + reported);
    }

    private void answer(final HttpExchange exchange) throws IOException
    {
        try (InputStream body = exchange.getRequestBody()) {
            final Charset charset = charsetOf(exchange.getRequestHeaders().getFirst("Content-Type"));
            this.recorder.bodies.add(charset.decode(ByteBuffer.wrap(body.readAllBytes())).toString());
        }
        this.recorder.contentTypes.add(exchange.getRequestHeaders().getFirst("Content-Type"));
        final byte[] answer = this.recorder.answer.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(this.recorder.status, answer.length == 0 ? -1 : answer.length);
        if (answer.length > 0) {
            exchange.getResponseBody().write(answer);
        }
        exchange.close();
    }

    private Charset charsetOf(final String contentType)
    {
        final int charsetAt = contentType.indexOf("charset=");
        return charsetAt < 0 ? StandardCharsets.UTF_8
            : Charset.forName(contentType.substring(charsetAt + "charset=".length()));
    }
}
