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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.uhndata.iap.httprequests.api.HttpResponse;
import io.uhndata.iap.httprequests.api.Multipart;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

    /** Why a request that could not be built was not made. */
    private static final String REFUSED = "Cannot post to the given address: it must be a valid http(s) URL, and every "
        + "header a valid one that may be set";

    private static final String MULTIPART = "multipart/form-data; boundary=";

    /** What the stand-in service answers with, and what it was asked. */
    private static final class Recorder
    {
        private int status = 200;

        private String answer = "ok";

        private final List<String> bodies = new ArrayList<>();

        private final List<byte[]> rawBodies = new ArrayList<>();

        private final List<String> contentTypes = new ArrayList<>();

        private final List<Integer> contentTypeCounts = new ArrayList<>();

        private final List<String> authorizations = new ArrayList<>();
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

        assertEquals(REFUSED, failure.getMessage());
        assertMentionsNoSecret(failure);
    }

    @Test
    void aContentTypeThatIsNotAValidHeaderIsAFailureToMakeTheRequest()
    {
        // Refusing the header the platform refuses is also what keeps a content type from injecting one of its own
        final IOException failure = assertThrows(IOException.class,
            () -> this.requests.post(this.url, "{}", "text/plain\r\nX-Injected: yes"));

        assertEquals(REFUSED, failure.getMessage());
        assertTrue(this.recorder.bodies.isEmpty());
    }

    @Test
    void postsEachPartInOrderWithTheGivenHeaders() throws IOException
    {
        // Bytes that are not text, and a line break that must not be read as the end of the part
        final byte[] binary = {0x00, (byte) 0xFF, '\r', '\n', 0x7F};
        final Multipart body = new Multipart()
            .add("manifest", "{\"é\":1}", "application/json")
            .add("b0", "scan.pdf", "application/pdf", () -> new ByteArrayInputStream(binary));

        final HttpResponse response = this.requests.post(this.url, Map.of("Authorization", "Bearer abc"), body);

        assertEquals(200, response.getStatusCode());
        assertEquals(List.of("Bearer abc"), this.recorder.authorizations);
        final String contentType = this.recorder.contentTypes.get(0);
        assertTrue(contentType.startsWith(MULTIPART), contentType);
        final String boundary = contentType.substring(MULTIPART.length());
        final ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.writeBytes(("--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"manifest\"\r\n"
            + "Content-Type: application/json\r\n\r\n"
            + "{\"é\":1}\r\n"
            + "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"b0\"; filename=\"scan.pdf\"\r\n"
            + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        expected.writeBytes(binary);
        expected.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(expected.toByteArray(), this.recorder.rawBodies.get(0));
    }

    @Test
    void aNameCannotBreakOutOfItsPartsHeaders() throws IOException
    {
        this.requests.post(this.url, Map.of(),
            new Multipart().add("a\"b\r\nX-Injected: yes", "c\".txt", "text/plain", InputStream::nullInputStream));

        final String sent = this.recorder.bodies.get(0);
        assertTrue(sent.contains("name=\"a%22b%0D%0AX-Injected: yes\"; filename=\"c%22.txt\"\r\n"), sent);
    }

    @Test
    void theBodyNamesItsOwnContentType() throws IOException
    {
        // Only the body knows its boundary, so a content type given with the headers cannot be the one sent
        this.requests.post(this.url, Map.of("Content-Type", "text/plain"), new Multipart());

        assertEquals(1, this.recorder.contentTypeCounts.get(0));
        assertTrue(this.recorder.contentTypes.get(0).startsWith(MULTIPART));
    }

    @Test
    void aPartThatCannotBeReadIsAFailureToMakeTheRequest()
    {
        final Multipart body = new Multipart().add("b0", "scan.pdf", "application/pdf", () -> {
            throw new IOException("The binary is gone");
        });

        assertThrows(IOException.class, () -> this.requests.post(this.url, Map.of(), body));
    }

    @Test
    void aHeaderThatCannotBeSetIsAFailureThatDoesNotQuoteIt()
    {
        for (final Map<String, String> headers : List.of(Map.of("Host", "elsewhere.example.com"),
            Map.of("Authorization", "Bearer " + SECRET + "\r\nX-Injected: yes"))) {
            final IOException failure =
                assertThrows(IOException.class, () -> this.requests.post(this.url, headers, new Multipart()));

            assertEquals(REFUSED, failure.getMessage());
            assertMentionsNoSecret(failure);
        }
        assertTrue(this.recorder.rawBodies.isEmpty());
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
            final byte[] raw = body.readAllBytes();
            final Charset charset = charsetOf(exchange.getRequestHeaders().getFirst("Content-Type"));
            this.recorder.bodies.add(charset.decode(ByteBuffer.wrap(raw)).toString());
            this.recorder.rawBodies.add(raw);
        }
        this.recorder.contentTypes.add(exchange.getRequestHeaders().getFirst("Content-Type"));
        this.recorder.contentTypeCounts.add(exchange.getRequestHeaders().get("Content-Type").size());
        final String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization != null) {
            this.recorder.authorizations.add(authorization);
        }
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
