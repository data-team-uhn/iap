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
        final JdkHttpRequests interrupted = new JdkHttpRequests()
        {
            @Override
            protected java.net.http.HttpResponse<String> send(final HttpRequest request) throws InterruptedException
            {
                throw new InterruptedException("stop what you are doing");
            }
        };

        final IOException failure =
            assertThrows(IOException.class, () -> interrupted.post(this.url, "{}", "application/json"));

        assertTrue(failure.getMessage().contains(this.url));
        // The thread stays interrupted, so whoever asked it to stop is not ignored
        assertTrue(Thread.interrupted());
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
