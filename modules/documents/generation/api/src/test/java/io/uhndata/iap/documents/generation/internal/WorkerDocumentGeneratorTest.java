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
package io.uhndata.iap.documents.generation.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import com.sun.net.httpserver.HttpServer;
import io.uhndata.iap.documents.generation.api.DocumentGenerator;
import io.uhndata.iap.documents.generation.api.GenerationResult;
import io.uhndata.iap.documents.generation.api.GenerationResult.Status;
import io.uhndata.iap.documents.generation.spi.TemplateContextProvider;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link WorkerDocumentGenerator}, against a real HTTP server standing in for the worker, so that what
 * travels over the wire (the body, the token, the timeout) is what is checked. Worth pinning down: which worker
 * replies are whose problem, and that a request is never sent when the template or the values are already wrong.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class WorkerDocumentGeneratorTest
{
    private static final byte[] TEMPLATE = "PK template bytes".getBytes(StandardCharsets.UTF_8);

    private static final byte[] DOCX = "PK filled docx".getBytes(StandardCharsets.UTF_8);

    private static final byte[] PDF = "%PDF-1.7 letter".getBytes(StandardCharsets.UTF_8);

    private final SlingContext context = new SlingContext();

    private HttpServer worker;

    /** What the stand-in worker answers next. */
    private int replyStatus;

    private String replyBody;

    /** How long the stand-in worker takes to answer. */
    private long replyDelayMillis;

    /** What the stand-in worker last received, {@code null} when nothing was sent. */
    private JsonObject received;

    private String receivedAuthorization;

    private String receivedContentType;

    private Resource template;

    private Resource target;

    @BeforeEach
    void setUp() throws IOException
    {
        this.worker = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.worker.createContext("/generate", exchange -> {
            this.received = Json.createReader(exchange.getRequestBody()).readObject();
            this.receivedAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            this.receivedContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            try {
                Thread.sleep(this.replyDelayMillis);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            final byte[] reply = this.replyBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(this.replyStatus, reply.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reply);
            }
        });
        this.worker.start();
        workerAnswers(200, generatedReply());

        this.template = this.context.load().binaryFile(new ByteArrayInputStream(TEMPLATE),
            "/Schemas/letter/decision/template.docx");
        this.target = this.context.create().resource("/Submissions/s1", Map.of("title", "Test my drug"));
    }

    @AfterEach
    void tearDown()
    {
        this.worker.stop(0);
    }

    @Test
    void sendsTheTemplateAndEveryProvidersValueAndAnswersWithBothDocuments() throws Exception
    {
        final WorkerDocumentGenerator generator = generator(null, null,
            provider("submission", Map.of("title", "Test my drug")),
            provider("submitter", Map.of("name", "Dr. A")));

        final GenerationResult result = generator.generate(this.template, this.target);

        assertEquals(Status.GENERATED, result.getStatus(), result.toString());
        assertNotNull(result.getDocument());
        assertArrayEquals(DOCX, result.getDocument().openDocx().readAllBytes());
        assertArrayEquals(PDF, result.getDocument().openPdf().readAllBytes());
        assertArrayEquals(TEMPLATE, Base64.getDecoder().decode(this.received.getString("template")));
        assertEquals("Test my drug",
            this.received.getJsonObject("context").getJsonObject("submission").getString("title"));
        assertEquals("Dr. A", this.received.getJsonObject("context").getJsonObject("submitter").getString("name"));
        assertEquals("application/json", this.receivedContentType);
        assertNull(this.receivedAuthorization, "no token is sent when none is configured");
    }

    @Test
    void sendsTheTokenFromTheEnvironmentWhenNoneIsConfigured()
    {
        generator(null, "from-environment").generate(this.template, this.target);

        assertEquals("Bearer from-environment", this.receivedAuthorization);
    }

    @Test
    void aConfiguredTokenWinsOverTheEnvironment()
    {
        generator("configured", "from-environment").generate(this.template, this.target);

        assertEquals("Bearer configured", this.receivedAuthorization);
    }

    @Test
    void aTemplateMistakeIsATemplateErrorCarryingTheWorkersMessage()
    {
        workerAnswers(422, "{\"error\": \"the template uses a value that was not provided: 'decision'\"}");

        final GenerationResult result = generator(null, null).generate(this.template, this.target);

        assertEquals(Status.TEMPLATE_ERROR, result.getStatus());
        assertTrue(result.getMessage().contains("'decision'"), "the template's author needs the worker's reason");
        assertNull(result.getDocument());
    }

    @Test
    void aBusyWorkerIsUnavailable()
    {
        workerAnswers(503, "{\"error\": \"worker busy: all 2 conversion slots in use\"}");

        final GenerationResult result = generator(null, null).generate(this.template, this.target);

        assertEquals(Status.UNAVAILABLE, result.getStatus());
        assertTrue(result.getMessage().contains("busy"));
    }

    @Test
    void aWorkerThatIsNotThereIsUnavailable()
    {
        final int port = this.worker.getAddress().getPort();
        this.worker.stop(0);

        final GenerationResult result = generator(null, null, "http://127.0.0.1:" + port, 40)
            .generate(this.template, this.target);

        assertEquals(Status.UNAVAILABLE, result.getStatus(), result.toString());
    }

    @Test
    void aWorkerThatDoesNotAnswerInTimeIsUnavailable()
    {
        this.replyDelayMillis = 3000;

        final GenerationResult result = generator(null, null, workerUrl(), 1).generate(this.template, this.target);

        assertEquals(Status.UNAVAILABLE, result.getStatus());
        assertTrue(result.getMessage().contains("1 seconds"), result.getMessage());
    }

    @Test
    void aRefusedTokenNeedsAnAdministrator()
    {
        workerAnswers(401, "{\"error\": \"missing or invalid IAP_GENERATION_TOKEN bearer token\"}");

        final GenerationResult result = generator(null, null).generate(this.template, this.target);

        assertEquals(Status.FAILED, result.getStatus());
        assertTrue(result.getMessage().contains("401"));
    }

    @Test
    void aWorkerFailureKeepsTheReferenceItsDetailsAreLoggedUnder()
    {
        workerAnswers(500, "{\"error\": \"the document could not be generated\", \"reference\": \"abc123\"}");

        final GenerationResult result = generator(null, null).generate(this.template, this.target);

        assertEquals(Status.FAILED, result.getStatus());
        assertTrue(result.getMessage().contains("abc123"), "the reference is how the worker's log is searched");
    }

    @Test
    void anAnswerThatIsNotTheWorkersFailsRatherThanStoringNothing()
    {
        workerAnswers(200, "{\"docx\": \"not base64 at all!\"}");

        final GenerationResult result = generator(null, null).generate(this.template, this.target);

        assertEquals(Status.FAILED, result.getStatus());
        assertNull(result.getDocument());
    }

    @Test
    void aResourceThatIsNotAFileIsATemplateErrorAndNothingIsSent()
    {
        final GenerationResult result = generator(null, null).generate(this.target, this.target);

        assertEquals(Status.TEMPLATE_ERROR, result.getStatus());
        assertNull(this.received, "nothing is sent for a template that is not there");
    }

    @Test
    void anOversizedTemplateIsRefusedBeforeItIsSent()
    {
        final Resource huge = this.context.load().binaryFile(
            new ByteArrayInputStream(new byte[DocumentGenerator.DEFAULT_MAX_TEMPLATE_BYTES + 1]),
            "/Schemas/letter/decision/huge.docx");

        final GenerationResult result = generator(null, null).generate(huge, this.target);

        assertEquals(Status.TEMPLATE_ERROR, result.getStatus());
        assertNull(this.received);
    }

    @Test
    void theEnvironmentSetsTheTemplateLimit()
    {
        final WorkerDocumentGenerator generator = generatorWithTemplateLimit("100");
        final Resource large = this.context.load().binaryFile(new ByteArrayInputStream(new byte[101]),
            "/Schemas/letter/decision/large.docx");

        final GenerationResult result = generator.generate(large, this.target);

        assertEquals(100, generator.getMaxTemplateBytes());
        assertEquals(Status.TEMPLATE_ERROR, result.getStatus());
        assertTrue(result.getMessage().contains("100-byte limit"), result.getMessage());
        assertNull(this.received);
    }

    @Test
    void aTemplateLimitThatIsNotAPositiveNumberIsIgnored()
    {
        for (final String limit : new String[] { null, "", " ", "ten megabytes", "0", "-1", "99999999999" }) {
            assertEquals(DocumentGenerator.DEFAULT_MAX_TEMPLATE_BYTES,
                generatorWithTemplateLimit(limit).getMaxTemplateBytes(), "the default stands in for " + limit);
        }
    }

    @Test
    void aSecondProviderForTheSameNameIsIgnored()
    {
        generator(null, null, provider("submission", "first"), provider("submission", "second"))
            .generate(this.template, this.target);

        assertEquals("first", this.received.getJsonObject("context").getString("submission"));
    }

    @Test
    void aProviderWithNothingForThisResourceLeavesItsNameOut()
    {
        generator(null, null, provider("submission", null)).generate(this.template, this.target);

        assertFalse(this.received.getJsonObject("context").containsKey("submission"),
            "a missing name has to stay missing, so that a template using it fails rather than prints None");
    }

    @Test
    void aProviderThatFailsFailsTheGenerationAndNothingIsSent()
    {
        final TemplateContextProvider broken = Mockito.mock(TemplateContextProvider.class);
        Mockito.when(broken.getName()).thenReturn("submission");
        Mockito.when(broken.getValue(Mockito.any())).thenThrow(new IllegalStateException("no answers node"));

        final GenerationResult result = generator(null, null, broken).generate(this.template, this.target);

        assertEquals(Status.FAILED, result.getStatus());
        assertTrue(result.getMessage().contains("submission") && result.getMessage().contains("no answers node"));
        assertNull(this.received);
    }

    @Test
    void aValueNoTemplateCanHoldFailsNamingWhereItIs()
    {
        final GenerationResult result = generator(null, null,
            provider("submission", Map.of("answers", List.of(new Object())))).generate(this.template, this.target);

        assertEquals(Status.FAILED, result.getStatus());
        assertTrue(result.getMessage().contains("submission.answers[0]"), result.getMessage());
        assertNull(this.received);
    }

    @Test
    void aBlankConfigurationFallsBackToWorkingDefaults() throws ReflectiveOperationException
    {
        final WorkerDocumentGenerator generator = new WorkerDocumentGenerator();
        final WorkerDocumentGenerator.Configuration blank = Mockito.mock(WorkerDocumentGenerator.Configuration.class);
        Mockito.when(blank.workerUrl()).thenReturn(null);
        Mockito.when(blank.workerToken()).thenReturn(null);
        Mockito.when(blank.responseTimeout()).thenReturn(0);

        generator.activate(blank);

        assertEquals(WorkerDocumentGenerator.DEFAULT_WORKER_URL, field(generator, "workerUrl"));
        assertEquals(Duration.ofSeconds(WorkerDocumentGenerator.DEFAULT_RESPONSE_TIMEOUT),
            field(generator, "responseTimeout"));
    }

    @Test
    void aTrailingSlashOnTheUrlIsDropped()
    {
        final GenerationResult result = generator(null, null, workerUrl() + "//", 40)
            .generate(this.template, this.target);

        assertEquals(Status.GENERATED, result.getStatus(), "the request must go to /generate, not //generate");
    }

    @Test
    void withNoProvidersRegisteredTheTemplateGetsNoValues()
    {
        final WorkerDocumentGenerator generator = generator(null, null);
        inject(generator, null);

        generator.generate(this.template, this.target);

        assertEquals(JsonValue.EMPTY_JSON_OBJECT, this.received.getJsonObject("context"));
    }

    @Test
    void aTemplateThatCannotBeReadNeedsAnAdministrator()
    {
        final Resource unreadable = Mockito.mock(Resource.class);
        Mockito.when(unreadable.getPath()).thenReturn("/Schemas/letter/decision/template.docx");
        Mockito.when(unreadable.adaptTo(InputStream.class)).thenReturn(new InputStream()
        {
            @Override
            public int read() throws IOException
            {
                throw new IOException("binary is gone");
            }
        });

        final GenerationResult result = generator(null, null).generate(unreadable, this.target);

        assertEquals(Status.FAILED, result.getStatus());
        assertTrue(result.getMessage().contains("binary is gone"));
        assertNull(this.received);
    }

    @Test
    void anInterruptedWaitIsUnavailableAndKeepsTheInterrupt()
    {
        final WorkerDocumentGenerator generator = new WorkerDocumentGenerator()
        {
            @Override
            protected HttpResponse<String> send(final HttpRequest request) throws InterruptedException
            {
                throw new InterruptedException();
            }
        };
        generator.activate(configuration(workerUrl(), "", 40));

        final GenerationResult result = generator.generate(this.template, this.target);

        // Read and cleared in one go, so the interrupt does not leak into the next test
        assertTrue(Thread.interrupted(), "the thread must still know it was interrupted");
        assertEquals(Status.UNAVAILABLE, result.getStatus());
    }

    @Test
    void anAnswerThatIsNotJsonFails()
    {
        workerAnswers(200, "<html>proxy error</html>");

        assertEquals(Status.FAILED, generator(null, null).generate(this.template, this.target).getStatus());
    }

    @Test
    void anAnswerWhoseDocumentsAreNotBase64Fails()
    {
        workerAnswers(200, "{\"docx\": \"%%%\", \"pdf\": \"%%%\"}");

        assertEquals(Status.FAILED, generator(null, null).generate(this.template, this.target).getStatus());
    }

    @Test
    void aRefusalThatIsNotTheWorkersJsonIsQuotedAsItCame()
    {
        workerAnswers(502, "Bad gateway");
        assertTrue(generator(null, null).generate(this.template, this.target).getMessage().endsWith("Bad gateway"));

        workerAnswers(502, "{\"detail\": \"no error key\"}");
        assertTrue(generator(null, null).generate(this.template, this.target).getMessage()
            .endsWith("{\"detail\": \"no error key\"}"));
    }

    @Test
    void aLongRefusalIsCutShort()
    {
        workerAnswers(502, "x".repeat(1000));

        final String message = generator(null, null).generate(this.template, this.target).getMessage();

        assertTrue(message.endsWith("…") && message.length() < 300, message);
    }

    @Test
    void anEmptyRefusalSaysSo()
    {
        workerAnswers(500, "");

        assertTrue(generator(null, null).generate(this.template, this.target).getMessage()
            .contains("(empty response)"));
    }

    private void workerAnswers(final int status, final String body)
    {
        this.replyStatus = status;
        this.replyBody = body;
    }

    private static String generatedReply()
    {
        return Json.createObjectBuilder()
            .add("docx", Base64.getEncoder().encodeToString(DOCX))
            .add("pdf", Base64.getEncoder().encodeToString(PDF))
            .build()
            .toString();
    }

    private String workerUrl()
    {
        return "http://127.0.0.1:" + this.worker.getAddress().getPort();
    }

    private WorkerDocumentGenerator generator(final String configuredToken, final String environmentToken,
        final TemplateContextProvider... providers)
    {
        return generator(configuredToken, environmentToken, workerUrl(), 40, providers);
    }

    private WorkerDocumentGenerator generator(final String configuredToken, final String environmentToken,
        final String url, final int timeout, final TemplateContextProvider... providers)
    {
        final WorkerDocumentGenerator generator = new WorkerDocumentGenerator()
        {
            @Override
            protected String environment(final String name)
            {
                return WorkerDocumentGenerator.TOKEN_VARIABLE.equals(name) ? environmentToken : null;
            }
        };
        generator.activate(configuration(url, configuredToken == null ? "" : configuredToken, timeout));
        inject(generator, List.of(providers));
        return generator;
    }

    private WorkerDocumentGenerator generatorWithTemplateLimit(final String limit)
    {
        final WorkerDocumentGenerator generator = new WorkerDocumentGenerator()
        {
            @Override
            protected String environment(final String name)
            {
                return WorkerDocumentGenerator.MAX_TEMPLATE_BYTES_VARIABLE.equals(name) ? limit : null;
            }
        };
        generator.activate(configuration(workerUrl(), "", 40));
        inject(generator, List.of());
        return generator;
    }

    private static WorkerDocumentGenerator.Configuration configuration(final String url, final String token,
        final int timeout)
    {
        final WorkerDocumentGenerator.Configuration configuration =
            Mockito.mock(WorkerDocumentGenerator.Configuration.class);
        Mockito.when(configuration.workerUrl()).thenReturn(url);
        Mockito.when(configuration.workerToken()).thenReturn(token);
        Mockito.when(configuration.responseTimeout()).thenReturn(timeout);
        return configuration;
    }

    private static void inject(final WorkerDocumentGenerator generator, final List<TemplateContextProvider> providers)
    {
        try {
            final Field field = WorkerDocumentGenerator.class.getDeclaredField("providers");
            field.setAccessible(true);
            field.set(generator, providers);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object field(final WorkerDocumentGenerator generator, final String name)
        throws ReflectiveOperationException
    {
        final Field field = WorkerDocumentGenerator.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(generator);
    }

    private static TemplateContextProvider provider(final String name, final Object value)
    {
        return new TemplateContextProvider()
        {
            @Override
            public @NotNull String getName()
            {
                return name;
            }

            @Override
            public Object getValue(final @NotNull Resource target)
            {
                return value;
            }
        };
    }
}
