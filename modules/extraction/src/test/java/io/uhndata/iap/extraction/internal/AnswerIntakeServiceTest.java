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
package io.uhndata.iap.extraction.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.extraction.internal.AnswerIntakeService.IntakeResult;
import io.uhndata.iap.llm.CallBudget;
import io.uhndata.iap.llm.LLMClient;
import io.uhndata.iap.llm.LLMClientFactory;
import io.uhndata.iap.llm.LLMConfigurationService;
import io.uhndata.iap.llm.LLMMessage;
import io.uhndata.iap.llm.LLMRequestOptions;
import io.uhndata.iap.llm.LLMSettings;
import io.uhndata.iap.submissions.models.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AnswerIntakeService}: planning the calls, reading the replies, and when it gives up instead
 * of guessing.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class AnswerIntakeServiceTest
{
    /** A paragraph break. A long document is cut at one. */
    private static final String BREAK = "\n\n";

    private static final String FILE_PATH = "/Submissions/aa/proposal/v1/file";

    private static final String AIMS = "study.aims";

    /** The document every test reads. */
    private static final String DOCUMENT =
        "# Protocol\n\n## Objectives\n\nThe aim is to find out whether it works.\n";

    /** A sentence that is in {@link #DOCUMENT}. */
    private static final String REAL_QUOTE = "The aim is to find out whether it works.";

    /** A window that holds the prompt and some of a document, but not 20,000 characters of one. */
    private static final long SMALL_WINDOW = 5000;

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private AnswerIntakeService intake;

    private StubClient client;

    private IOException settingsFailure;

    private IOException clientFailure;

    /** The model's context window in tokens. 0 means the settings do not give one. */
    private long window;

    /** A fake model: returns the replies the test queued, and records what it was sent. */
    private static final class StubClient implements LLMClient
    {
        private final List<Object> replies = new ArrayList<>();

        private final List<String> asked = new ArrayList<>();

        private String system;

        @Override
        public String chat(final String systemPrompt, final List<LLMMessage> messages,
            final LLMRequestOptions requestOptions) throws IOException
        {
            this.system = systemPrompt;
            this.asked.add(messages.get(0).getContent());
            final Object reply = this.replies.isEmpty() ? "" : this.replies.remove(0);
            if (reply instanceof final IOException failure) {
                throw failure;
            }
            return (String) reply;
        }
    }

    @BeforeEach
    void setUp() throws Exception
    {
        this.intake = new AnswerIntakeService();
        this.client = new StubClient();
        inject("llmClientFactory", (LLMClientFactory) () -> {
            if (this.clientFailure != null) {
                throw this.clientFailure;
            }
            return this.client;
        });
        inject("configurationService", (LLMConfigurationService) () -> {
            if (this.settingsFailure != null) {
                throw this.settingsFailure;
            }
            return new LLMSettings("local", new LLMSettings.ProviderSettings(null, null, 0, null), "m",
                new LLMSettings.ModelSettings(this.window, 0.0, null, null));
        });
        this.context.create().resource(FILE_PATH, Map.of("sling:resourceType", File.RESOURCE_TYPE));
    }

    private void inject(final String name, final Object value) throws Exception
    {
        final Field field = AnswerIntakeService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(this.intake, value);
    }

    private File getFile()
    {
        final File model = this.context.resourceResolver().getResource(FILE_PATH).adaptTo(File.class);
        assertNotNull(model);
        return model;
    }

    private void addMarkdown(final String text)
    {
        final Resource file = this.context.create().resource(FILE_PATH + "/markdownFile",
            Map.of("jcr:primaryType", "nt:file"));
        this.context.create().resource(file.getPath() + "/jcr:content", Map.of(
            "jcr:primaryType", "nt:resource",
            "jcr:data", new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))));
    }

    private static ExtractionField createField(final String key)
    {
        return new ExtractionField(key, "What are the aims?", null, "Whether there is one", "Find the aims.",
            new ExtractionField.Answer(ExtractionField.TEXT_TYPE, false, null, null, null, List.of()));
    }

    private static List<ExtractionPrompt.Group> groupOf(final String... keys)
    {
        return List.of(new ExtractionPrompt.Group("study", List.of(keys).stream()
            .map(AnswerIntakeServiceTest::createField).toList()));
    }

    /** One field's answer as JSON, the way the model sends it. */
    private static String buildAnswer(final String key, final String value, final String quote)
    {
        return "{\"" + key + "\": {\"evidence\": [{\"quote\": \"" + quote + "\", \"page\": 3}],"
            + " \"reasoning\": \"It says so.\", \"value\": \"" + value + "\", \"confidence\": 0.9}}";
    }

    /** Keys q0 to q(count - 1). */
    private static String[] createKeys(final int count)
    {
        return IntStream.range(0, count).mapToObj(i -> "q" + i).toArray(String[]::new);
    }

    /** A reply that finds nothing for questions q(from) to q(to - 1). */
    private static String buildNotFound(final int from, final int to)
    {
        return IntStream.range(from, to).mapToObj(i -> "\"q" + i + "\": {\"value\": null}")
            .collect(Collectors.joining(", ", "{", "}"));
    }

    /** The document read and prepared once, the way the service gets it. */
    private DocumentScan scan() throws IOException
    {
        return new ParsedDocuments().scan(getFile());
    }

    private IntakeResult run(final List<ExtractionPrompt.Group> groups) throws IOException
    {
        return this.intake.askQuestions(getFile(), scan(), groups, null);
    }

    @Test
    void asksNothingWhenThereIsNothingToAsk() throws IOException
    {
        addMarkdown(DOCUMENT);

        for (final List<ExtractionPrompt.Group> nothing : List.of(List.<ExtractionPrompt.Group>of(),
            List.of(new ExtractionPrompt.Group("empty", List.of())))) {
            final IntakeResult result = run(nothing);
            assertTrue(result.fields().isEmpty());
            assertEquals(AnswerIntakeService.Status.READ, result.status());
        }
        assertTrue(this.client.asked.isEmpty(), "no call is worth making for no questions");
    }

    @Test
    void readsWhatTheModelFoundAndBackedWithAQuote() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.client.replies.add(buildAnswer(AIMS, "To find out whether it works", REAL_QUOTE));

        final IntakeResult result = run(groupOf(AIMS));

        assertEquals(AnswerIntakeService.Status.READ, result.status());
        assertNull(result.message());
        final FieldResult found = result.fields().get(AIMS);
        assertEquals(List.of("To find out whether it works"), found.values());
        assertEquals(0.9, found.confidence(), 1e-9, "every quote checked out, so nothing is taken off");
        assertEquals("Objectives", found.passages().get(0).header());
    }

    @Test
    void opensWithTheExtractionPromptAndTheDocument() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.client.replies.add("{}");

        run(groupOf(AIMS));

        assertEquals(ExtractionPrompt.readSystemPrompt(), this.client.system);
        assertTrue(this.client.asked.get(0).startsWith("<document>\n# Protocol"), this.client.asked.get(0));
    }

    // Knowledge only one step needs comes after the document, so every call on it still opens the same way
    @Test
    void sendsTheReferenceTextAfterTheDocument() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.client.replies.add("{}");

        this.intake.askQuestions(getFile(), scan(), groupOf(AIMS), "Only this reading.");

        final String asked = this.client.asked.get(0);
        assertTrue(asked.indexOf("</document>") < asked.indexOf("Only this reading."), asked);
        assertTrue(asked.indexOf("Only this reading.") < asked.indexOf("<questions>"), asked);
    }

    @Test
    void asksAtMostTenQuestionsPerCall() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.client.replies.add(buildNotFound(0, 10));
        this.client.replies.add(buildNotFound(10, 20));
        this.client.replies.add(buildNotFound(20, 23));

        final IntakeResult result = run(groupOf(createKeys(23)));

        assertEquals(3, this.client.asked.size());
        assertEquals(23, result.fields().size());
    }

    // A failed call loses its own questions; they get no run, so the next reading asks them again
    @Test
    void keepsWhatTheOtherCallsReadWhenOneCannotBeRead() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.client.replies.add("nonsense");
        this.client.replies.add("more nonsense");
        this.client.replies.add(buildNotFound(10, 11));

        final IntakeResult result = run(groupOf(createKeys(11)));

        assertEquals(AnswerIntakeService.Status.INCOMPLETE, result.status());
        assertEquals(IntakeResult.INCOMPLETE_MESSAGE, result.message());
        assertEquals(List.of("q10"), List.copyOf(result.fields().keySet()));
    }

    // The calls after an outage would each wait out the same timeout
    @Test
    void stopsAskingOnceTheModelCannotBeReached() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.client.replies.add(buildNotFound(0, 10));
        this.client.replies.add(new IOException("connection reset"));

        final IntakeResult result = run(groupOf(createKeys(21)));

        assertEquals(2, this.client.asked.size(), "the third call is never made");
        assertEquals(AnswerIntakeService.Status.INCOMPLETE, result.status());
        assertEquals(10, result.fields().size());
    }

    @Test
    void reportsAnUnreachableModelWhenNoCallWentThrough() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.client.replies.add(new IOException("connection refused"));

        final IntakeResult result = run(groupOf(AIMS));

        assertEquals(AnswerIntakeService.Status.NOT_READ, result.status());
        assertEquals(IntakeResult.UNREACHABLE_MESSAGE, result.message());
    }

    @Test
    void reportsAnUnreachableModelWhenNoModelIsSetUp() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.clientFailure = new IOException("no active provider");

        final IntakeResult result = run(groupOf(AIMS));

        assertEquals(AnswerIntakeService.Status.NOT_READ, result.status());
        assertEquals(IntakeResult.UNREACHABLE_MESSAGE, result.message());
    }

    // After two unreadable replies every question is left for the submitter to answer
    @Test
    void givesUpAfterTwoUnreadableRepliesWithoutGuessing() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.client.replies.add("nonsense");
        this.client.replies.add("more nonsense");

        final IntakeResult result = run(groupOf(AIMS));

        assertEquals(AnswerIntakeService.Status.NOT_READ, result.status());
        assertTrue(result.fields().isEmpty());
        assertEquals(IntakeResult.REPLY_UNREADABLE_MESSAGE, result.message());
    }

    @Test
    void hasNothingToAskAboutADocumentWithNoText() throws IOException
    {
        final IntakeResult result = run(groupOf(AIMS));

        assertEquals(AnswerIntakeService.Status.NOT_READ, result.status());
        assertEquals("The document has no text that can be read", result.message());
        assertTrue(this.client.asked.isEmpty(), "nothing was worth asking");
    }

    @Test
    void fallsBackToTheDefaultBudgetWhenTheSettingsCannotBeRead() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.settingsFailure = new IOException("no active provider");
        this.client.replies.add(buildAnswer(AIMS, "Yes", REAL_QUOTE));

        assertTrue(run(groupOf(AIMS)).fields().get(AIMS).isFound(), "a settings problem must not stop the reading");
    }

    // Cutting the middle keeps the provider from refusing a long document. The end is kept because a protocol
    // puts its appendices and signature page there.
    @Test
    void cutsTheMiddleOfADocumentTooLongForTheWindowAndKeepsBothEnds() throws IOException
    {
        this.window = SMALL_WINDOW;
        addMarkdown(DOCUMENT + BREAK + "x".repeat(20_000) + BREAK + "THE END");
        this.client.replies.add("{}");

        run(groupOf(AIMS));

        final String asked = this.client.asked.get(0);
        assertTrue(asked.contains("THE END"), "the end is sent");
        assertTrue(asked.contains(CallBudget.OMISSION_MARKER.strip()), "and the gap is marked");
        assertFalse(asked.contains("x".repeat(20_000)), "but not the whole middle");
    }

    // A quote is real if it is anywhere in the file the submitter attached, not only in the part that was sent
    @Test
    void checksQuotesAgainstTheWholeDocumentEvenWhenItWasCut() throws IOException
    {
        this.window = SMALL_WINDOW;
        final String middle = "The middle says the sample is 182 adults.";
        addMarkdown(DOCUMENT + BREAK + "x".repeat(10_000) + BREAK + middle + BREAK + "y".repeat(10_000));
        this.client.replies.add(buildAnswer(AIMS, "182", middle));

        final FieldResult found = run(groupOf(AIMS)).fields().get(AIMS);

        assertFalse(this.client.asked.get(0).contains(middle), "the middle was not sent");
        assertEquals(1, found.passages().size(), "but the quote from it still counts");
    }

    @Test
    void readsNothingWhenTheQuestionsLeaveNoRoomForTheDocumentAtAll() throws IOException
    {
        this.window = 1;
        addMarkdown(DOCUMENT);

        final IntakeResult result = run(groupOf(AIMS));

        assertEquals(AnswerIntakeService.Status.NOT_READ, result.status(),
            "asking every question over no document could only invent");
        assertTrue(this.client.asked.isEmpty(), "and the call is not made at all");
        assertEquals("The questions leave no room to show the model the document", result.message());
    }
}
