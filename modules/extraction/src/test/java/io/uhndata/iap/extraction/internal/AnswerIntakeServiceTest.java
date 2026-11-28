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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.errortracking.api.ErrorLoggerService;
import io.uhndata.iap.extraction.internal.AnswerIntakeService.IntakeResult;
import io.uhndata.iap.llm.LLMClient;
import io.uhndata.iap.llm.LLMClientFactory;
import io.uhndata.iap.llm.LLMConfigurationService;
import io.uhndata.iap.llm.LLMMessage;
import io.uhndata.iap.llm.LLMReply;
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
    /** A paragraph break, between the parts of a test document. */
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

    /** Leaves room for less than {@link ExtractionPrompt#MIN_DOCUMENT_TOKENS} beside one question. */
    private static final long TINY_WINDOW = 3000;

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private AnswerIntakeService intake;

    private StubClient client;

    private IOException settingsFailure;

    private IOException clientFailure;

    /** The model's context window in tokens. 0 means the settings do not give one. */
    private long window;

    private final ErrorLoggerService errors = Mockito.mock(ErrorLoggerService.class);

    /** A fake model: returns the replies the test queued, and records what it was sent. */
    private static final class StubClient implements LLMClient
    {
        private final List<Object> replies = new ArrayList<>();

        private final List<String> asked = new ArrayList<>();

        private String system;

        @Override
        public LLMReply chatWithDetails(final String systemPrompt, final List<LLMMessage> messages,
            final LLMRequestOptions requestOptions) throws IOException
        {
            this.system = systemPrompt;
            this.asked.add(messages.get(0).getContent());
            final Object reply = this.replies.isEmpty() ? "" : this.replies.remove(0);
            if (reply instanceof final IOException failure) {
                throw failure;
            }
            return new LLMReply((String) reply, null, null, null);
        }
    }

    @BeforeEach
    void setUp() throws Exception
    {
        this.intake = new AnswerIntakeService();
        this.client = new StubClient();
        TestComponents.inject(this.intake, "llmClientFactory", (LLMClientFactory) () -> {
            if (this.clientFailure != null) {
                throw this.clientFailure;
            }
            return this.client;
        });
        TestComponents.inject(this.intake, "configurationService", (LLMConfigurationService) () -> {
            if (this.settingsFailure != null) {
                throw this.settingsFailure;
            }
            return new LLMSettings("local", new LLMSettings.ProviderSettings(null, null, 0, null), "m",
                new LLMSettings.ModelSettings(this.window, 0.0, null, null));
        });
        this.context.create().resource(FILE_PATH, Map.of("sling:resourceType", File.RESOURCE_TYPE));
        ErrorLogger.setService(this.errors);
    }

    @AfterEach
    void tearDown()
    {
        ErrorLogger.unsetService(this.errors);
    }

    private File getFile()
    {
        final File model = this.context.resourceResolver().getResource(FILE_PATH).adaptTo(File.class);
        assertNotNull(model);
        return model;
    }

    private void addMarkdown(final String text)
    {
        SubmissionTree.storeText(this.context, FILE_PATH + "/markdownFile", text);
    }

    private static List<ExtractionPrompt.Group> createStudyGroups(final String... keys)
    {
        return List.of(TestFields.createGroup("study", keys));
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
    void asksNothingWhenThereAreNoGroups() throws IOException
    {
        addMarkdown(DOCUMENT);

        final IntakeResult result = run(List.of());

        assertTrue(result.fields().isEmpty());
        assertEquals(AnswerIntakeService.Status.READ, result.status());
        assertTrue(this.client.asked.isEmpty(), "no call is worth making for no questions");
    }

    @Test
    void asksNothingWhenTheGroupsHoldNoQuestions() throws IOException
    {
        addMarkdown(DOCUMENT);

        final IntakeResult result = run(List.of(new ExtractionPrompt.Group("empty", List.of())));

        assertTrue(result.fields().isEmpty());
        assertEquals(AnswerIntakeService.Status.READ, result.status());
        assertTrue(this.client.asked.isEmpty(), "no call is worth making for no questions");
    }

    @Test
    void readsWhatTheModelFoundAndBackedWithAQuote() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.client.replies.add(buildAnswer(AIMS, "To find out whether it works", REAL_QUOTE));

        final IntakeResult result = run(createStudyGroups(AIMS));

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

        run(createStudyGroups(AIMS));

        assertEquals(ExtractionPrompt.readSystemPrompt(), this.client.system);
        assertTrue(this.client.asked.get(0).startsWith("<document>\n# Protocol"), this.client.asked.get(0));
    }

    // Knowledge only one step needs comes after the document, so every call on it still opens the same way
    @Test
    void sendsTheReferenceTextAfterTheDocument() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.client.replies.add("{}");

        this.intake.askQuestions(getFile(), scan(), createStudyGroups(AIMS), "Only this reading.");

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

        final IntakeResult result = run(createStudyGroups(createKeys(23)));

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

        final IntakeResult result = run(createStudyGroups(createKeys(11)));

        assertEquals(AnswerIntakeService.Status.INCOMPLETE, result.status());
        assertEquals(IntakeResult.INCOMPLETE_MESSAGE, result.message());
        assertEquals(List.of("q10"), List.copyOf(result.fields().keySet()));
        assertFalse(result.isModelFailing(), "the model answered, so the next document can still be asked");
    }

    // The calls after an outage would each wait out the same timeout
    @Test
    void stopsAskingOnceTheModelCannotBeReached() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.client.replies.add(buildNotFound(0, 10));
        this.client.replies.add(new IOException("connection reset"));

        final IntakeResult result = run(createStudyGroups(createKeys(21)));

        assertEquals(2, this.client.asked.size(), "the third call is never made");
        assertEquals(AnswerIntakeService.Status.INCOMPLETE, result.status());
        assertEquals(10, result.fields().size());
        assertTrue(result.isModelFailing());
        Mockito.verify(this.errors).logError(Mockito.any(IOException.class), Mockito.any());
    }

    @Test
    void reportsAnUnreachableModelWhenNoCallWentThrough() throws IOException
    {
        addMarkdown(DOCUMENT);
        final IOException outage = new IOException("connection refused");
        this.client.replies.add(outage);

        final IntakeResult result = run(createStudyGroups(AIMS));

        assertEquals(AnswerIntakeService.Status.NOT_READ, result.status());
        assertEquals(IntakeResult.UNREACHABLE_MESSAGE, result.message());
        assertTrue(result.isModelFailing(), "another document would fail the same way");
        Mockito.verify(this.errors).logError(Mockito.same(outage), Mockito.any());
    }

    @Test
    void reportsAnUnreachableModelWhenNoModelIsSetUp() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.clientFailure = new IOException("no active provider");

        final IntakeResult result = run(createStudyGroups(AIMS));

        assertEquals(AnswerIntakeService.Status.NOT_READ, result.status());
        assertEquals(IntakeResult.UNREACHABLE_MESSAGE, result.message());
        assertTrue(result.isModelFailing(), "another document would fail the same way");
        Mockito.verify(this.errors).logError(Mockito.same(this.clientFailure), Mockito.any());
    }

    // After two unreadable replies every question is left for the submitter to answer
    @Test
    void givesUpAfterTwoUnreadableRepliesWithoutGuessing() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.client.replies.add("nonsense");
        this.client.replies.add("more nonsense");

        final IntakeResult result = run(createStudyGroups(AIMS));

        assertEquals(AnswerIntakeService.Status.NOT_READ, result.status());
        assertTrue(result.fields().isEmpty());
        assertEquals(IntakeResult.REPLY_UNREADABLE_MESSAGE, result.message());
        assertTrue(result.isModelFailing());
        Mockito.verify(this.errors).logProblem(Mockito.eq("The model did not answer in the required shape"),
            Mockito.any());
    }

    @Test
    void hasNothingToAskAboutADocumentWithNoText() throws IOException
    {
        final IntakeResult result = run(createStudyGroups(AIMS));

        assertEquals(AnswerIntakeService.Status.NOT_READ, result.status());
        assertEquals(IntakeResult.NO_TEXT_MESSAGE, result.message());
        assertFalse(result.isModelFailing(), "another document can still be read");
        assertTrue(this.client.asked.isEmpty(), "nothing was worth asking");
    }

    @Test
    void readsNothingWhenTheSettingsCannotBeRead() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.settingsFailure = new IOException("no active provider");

        final IntakeResult result = run(createStudyGroups(AIMS));

        assertEquals(AnswerIntakeService.Status.NOT_READ, result.status());
        assertEquals(IntakeResult.UNREACHABLE_MESSAGE, result.message());
        assertTrue(result.isModelFailing(), "a later reading may find the settings again");
        assertTrue(this.client.asked.isEmpty(), "a guessed window would refuse documents the model can read");
        Mockito.verify(this.errors).logError(Mockito.same(this.settingsFailure), Mockito.any());
    }

    @Test
    void readsNothingFromADocumentTooLongForTheWindow() throws IOException
    {
        this.window = SMALL_WINDOW;
        addMarkdown(DOCUMENT + BREAK + "x".repeat(20_000) + BREAK + "THE END");

        final IntakeResult result = run(createStudyGroups(AIMS));

        assertEquals(AnswerIntakeService.Status.TOO_LONG, result.status());
        assertTrue(result.isNothingRead());
        assertEquals(IntakeResult.TOO_LONG_MESSAGE, result.message());
        assertEquals(IntakeResult.TOO_LONG_MESSAGE, result.describeShortfall(), "the submitter is told why");
        assertFalse(result.isModelFailing(), "another document can still be read");
        assertTrue(this.client.asked.isEmpty(), "and the call is not made at all");
        Mockito.verifyNoInteractions(this.errors);
    }

    // Room for less than a page is the window's fault, not the document's, so an administrator is told
    @Test
    void blamesTheWindowWhenItLeavesRoomForLessThanAPage() throws IOException
    {
        this.window = TINY_WINDOW;
        addMarkdown(DOCUMENT + BREAK + "x".repeat(20_000));

        final IntakeResult result = run(createStudyGroups(AIMS));

        assertEquals(IntakeResult.NO_ROOM_MESSAGE, result.message());
        Mockito.verify(this.errors).logProblem(
            Mockito.eq("The questions leave no room for the document in the model's context window"), Mockito.any());
    }

    @Test
    void readsNothingWhenTheQuestionsLeaveNoRoomForTheDocumentAtAll() throws IOException
    {
        this.window = 1;
        addMarkdown(DOCUMENT);

        final IntakeResult result = run(createStudyGroups(AIMS));

        assertEquals(AnswerIntakeService.Status.NOT_READ, result.status(),
            "asking every question over no document could only invent");
        assertTrue(this.client.asked.isEmpty(), "and the call is not made at all");
        assertEquals(IntakeResult.NO_ROOM_MESSAGE, result.message());
        assertFalse(result.isModelFailing(), "another document can still be read");
        Mockito.verify(this.errors).logProblem(
            Mockito.eq("The questions leave no room for the document in the model's context window"), Mockito.any());
    }
}
