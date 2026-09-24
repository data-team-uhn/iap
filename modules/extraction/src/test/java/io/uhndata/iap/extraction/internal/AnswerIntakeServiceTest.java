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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AnswerIntakeService}: what it sends, what it believes, and what it refuses to guess.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class AnswerIntakeServiceTest
{
    /** What separates one paragraph from the next, which is where a cut lands. */
    private static final String BREAK = "\n\n";

    private static final String FILE_PATH = "/Submissions/aa/proposal/v1/file";

    private static final String AIMS = "aims";

    /** The document every test reads, with a heading a quote can be placed under. */
    private static final String DOCUMENT =
        "# Protocol\n\n## Objectives\n\nThe aim is to find out whether it works.\n";

    /** A sentence that really is in {@link #DOCUMENT}. */
    private static final String REAL_QUOTE = "The aim is to find out whether it works.";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private AnswerIntakeService intake;

    private StubClient client;

    private IOException settingsFailure;

    /** 0 means the model says nothing about its context window. */
    private long window;

    /** Answers with whatever the test lined up, and remembers what it was asked. */
    private static final class StubClient implements LLMClient
    {
        private final List<String> replies = new ArrayList<>();

        private final List<String> asked = new ArrayList<>();

        private final List<LLMRequestOptions> options = new ArrayList<>();

        private String system;

        private IOException failure;

        private String next()
        {
            return this.replies.isEmpty() ? "" : this.replies.remove(0);
        }

        @Override
        public String chat(final String systemPrompt, final List<LLMMessage> messages,
            final LLMRequestOptions requestOptions) throws IOException
        {
            if (this.failure != null) {
                throw this.failure;
            }
            this.system = systemPrompt;
            this.asked.add(messages.get(0).getContent());
            this.options.add(requestOptions);
            return next();
        }
    }

    @BeforeEach
    void setUp() throws Exception
    {
        this.intake = new AnswerIntakeService();
        this.client = new StubClient();
        inject("llmClientFactory", (LLMClientFactory) () -> this.client);
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

    private File file()
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

    private static ExtractionField aims()
    {
        return new ExtractionField(AIMS, "What are the aims?", "Whether there is one", "Find the aims.", null,
            false);
    }

    /** One field's answer, as the model would send it. */
    private static String answer(final String value, final double confidence, final String evidence)
    {
        return "{\"" + AIMS + "\":{\"found_answer\":true,\"value\":\"" + value + "\",\"confidence\":"
            + confidence + ",\"reasoning\":\"It says so.\",\"evidence\":[" + evidence + "]}}";
    }

    private static String quote(final String text)
    {
        return "{\"quote\":\"" + text + "\",\"page\":3}";
    }

    /** The document as the service is handed it now: read and prepared once, not per quote. */
    private DocumentScan scan() throws IOException
    {
        return new ParsedDocuments().scan(file());
    }

    private IntakeResult run() throws IOException
    {
        return this.intake.run(file(), scan(), List.of(aims()));
    }

    @Test
    void asksNothingWhenThereIsNothingToExtract() throws IOException
    {
        addMarkdown(DOCUMENT);

        final IntakeResult result = this.intake.run(file(), scan(), List.of());

        assertTrue(result.fields().isEmpty());
        assertFalse(result.degraded());
        assertTrue(this.client.asked.isEmpty(), "no call is worth making for no fields");
    }

    @Test
    void readsAFieldTheModelFoundAndBackedWithAQuote() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.client.replies.add(answer("To find out whether it works", 0.9, quote(REAL_QUOTE)));

        final FieldResult found = run().fields().get(AIMS);

        assertTrue(found.found());
        assertEquals("To find out whether it works", found.value());
        assertEquals(0.9, found.confidence(), 1e-9, "every quote checked out, so nothing is taken off");
        assertEquals("It says so.", found.reasoning());
        assertEquals(1, found.passages().size());
        assertEquals(3L, found.passages().get(0).page());
    }

    // The heading a reader would say the quote sits under, so the form can show where it came from
    @Test
    void placesAQuoteUnderTheHeadingAboveIt() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.client.replies.add(answer("Yes", 0.9, quote(REAL_QUOTE)));

        assertEquals("Objectives", run().fields().get(AIMS).passages().get(0).header());
    }

    @Test
    void discountsAnAnswerNoneOfWhoseQuotesCanBeFound() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.client.replies.add(answer("Yes", 0.9, quote("A sentence that is nowhere in the document.")));

        final FieldResult found = run().fields().get(AIMS);

        assertTrue(found.found(), "the answer may still be right; only its evidence failed");
        assertEquals(0.45, found.confidence(), 1e-9);
        assertTrue(found.passages().isEmpty(), "a quote nobody can find is not shown as evidence");
    }

    @Test
    void discountsAnAnswerByHowMuchOfItsEvidenceHeldUp() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.client.replies.add(answer("Yes", 0.8,
            quote(REAL_QUOTE) + "," + quote("An invented sentence nobody wrote.")));

        final FieldResult found = run().fields().get(AIMS);

        assertEquals(1, found.passages().size(), "only the real one is kept");
        assertEquals(0.8 * 0.75, found.confidence(), 1e-9, "one of two quotes costs a quarter");
    }

    // No evidence at all is treated exactly like evidence that all failed
    @Test
    void discountsAnAnswerWithNoEvidenceAtAll() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.client.replies.add(answer("Yes", 0.9, ""));

        assertEquals(0.45, run().fields().get(AIMS).confidence(), 1e-9);
    }

    @Test
    void anAnswerWithNoValueIsNotAnAnswer() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.client.replies.add("{\"" + AIMS + "\":{\"found_answer\":true,\"value\":null,"
            + "\"confidence\":0.9,\"reasoning\":\"\",\"evidence\":[]}}");

        final FieldResult found = run().fields().get(AIMS);

        assertFalse(found.found());
        assertNull(found.value());
    }

    @Test
    void aFieldTheModelLeftOutIsNotFound() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.client.replies.add("{\"something_else\":{\"found_answer\":true}}");

        final FieldResult found = run().fields().get(AIMS);

        assertFalse(found.found());
        assertEquals(0.0, found.confidence());
        assertTrue(found.passages().isEmpty());
    }

    @Test
    void keepsOnlyTheEvidenceItCanUse() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.client.replies.add(answer("Yes", 0.9,
            "\"not an object\"," + quote(REAL_QUOTE)
                + ",{\"quote\":\"   \",\"page\":1},{\"page\":2}"));

        assertEquals(1, run().fields().get(AIMS).passages().size());
    }

    @Test
    void copesWithEvidenceThatIsNotAList() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.client.replies.add("{\"" + AIMS + "\":{\"found_answer\":true,\"value\":\"Yes\","
            + "\"confidence\":0.9,\"reasoning\":\"\",\"evidence\":\"a sentence\"}}");

        assertTrue(run().fields().get(AIMS).passages().isEmpty());
    }

    @Test
    void sendsTheInstructionsAsTheSystemPromptAndTheDocumentAsTheMessage() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.client.replies.add(answer("Yes", 0.9, quote(REAL_QUOTE)));

        run();

        assertTrue(this.client.system.contains("intake extraction engine"), this.client.system);
        assertTrue(this.client.asked.get(0).contains("## DOCUMENT"), this.client.asked.get(0));
        assertTrue(this.client.asked.get(0).contains(REAL_QUOTE));
    }

    @Test
    void asksForRoomForEveryFieldItPutsToTheModel() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.client.replies.add("{}");

        this.intake.run(file(), scan(), List.of(aims(), aims()));

        assertEquals(500 + 400 * 2, this.client.options.get(0).getMaxOutputTokens());
    }

    @Test
    void asksAgainWhenTheAnswerIsNotTheShapeItHadToBe() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.client.replies.add("I could not do that.");
        this.client.replies.add(answer("Yes", 0.9, quote(REAL_QUOTE)));

        assertTrue(run().fields().get(AIMS).found());
        assertEquals(2, this.client.asked.size());
    }

    @Test
    void tellsTheModelWhatWasActuallyWrongWhenItAsksAgain() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.client.replies.add("{\"aims\": {\"found_answer\": true");
        this.client.replies.add(answer("Yes", 0.9, ""));

        run();

        assertTrue(this.client.asked.get(1).contains("cut off before it finished"), this.client.asked.get(1));
    }

    @Test
    void tellsTheModelWhenItSaidNothingAtAll() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.client.replies.add("");
        this.client.replies.add(answer("Yes", 0.9, ""));

        run();

        assertTrue(this.client.asked.get(1).contains("it was empty"), this.client.asked.get(1));
    }

    @Test
    void tellsTheModelWhenItAnsweredInProseInstead() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.client.replies.add("The aims are to find out whether it works.");
        this.client.replies.add(answer("Yes", 0.9, ""));

        run();

        assertTrue(this.client.asked.get(1).contains("no JSON object"), this.client.asked.get(1));
    }

    @Test
    void tellsTheModelWhenItAnsweredWithTheWrongKeys() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.client.replies.add("{\"aims\": }");
        this.client.replies.add(answer("Yes", 0.9, ""));

        run();

        assertTrue(this.client.asked.get(1).contains("a key for each of 1 fields"), this.client.asked.get(1));
    }

    // Two unreadable answers settle nothing: every field is left for the submitter rather than guessed at
    @Test
    void givesUpAfterTwoUnreadableAnswersWithoutGuessing() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.client.replies.add("nonsense");
        this.client.replies.add("more nonsense");

        final IntakeResult result = run();

        assertTrue(result.degraded());
        assertTrue(result.fields().isEmpty());
    }

    @Test
    void hasNothingToAskAboutADocumentWithNoText() throws IOException
    {
        final IntakeResult result = run();

        assertTrue(result.degraded());
        assertTrue(this.client.asked.isEmpty(), "nothing was worth asking");
    }

    @Test
    void hasNothingToAskAboutADocumentThatIsAllWhitespace() throws IOException
    {
        addMarkdown("   \n\n  ");

        assertTrue(run().degraded());
    }

    @Test
    void letsAnUnreachableModelBeReportedRatherThanHidden()
    {
        addMarkdown(DOCUMENT);
        this.client.failure = new IOException("the model is unreachable");

        final IOException thrown = assertThrows(IOException.class, this::run);

        assertEquals("the model is unreachable", thrown.getMessage());
    }

    @Test
    void fallsBackToTheDefaultBudgetWhenTheSettingsCannotBeRead() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.settingsFailure = new IOException("no active provider");
        this.client.replies.add(answer("Yes", 0.9, quote(REAL_QUOTE)));

        assertTrue(run().fields().get(AIMS).found(), "a settings problem must not stop the reading");
    }

    // The window holds the questions and the answer as well, so the document gets well under all of it
    @Test
    void leavesTheModelRoomForTheQuestionsAndTheAnswer() throws IOException
    {
        this.window = 20_000;
        addMarkdown("x".repeat(20_000 * CallBudget.CHARS_PER_TOKEN));
        this.client.replies.add("{}");

        run();

        final long sent = this.client.asked.get(0).length() / CallBudget.CHARS_PER_TOKEN;
        assertTrue(sent > 0 && sent <= 17_000, "the window less its margin, the answer and the questions");
    }

    // Cutting the middle is what keeps a long document from being refused outright by the provider, and the end
    // goes because that is where a protocol keeps what is asked about last
    @Test
    void cutsTheMiddleOfADocumentTooLongForTheWindowAndKeepsBothEnds() throws IOException
    {
        this.window = 2000;
        addMarkdown(DOCUMENT + BREAK + "x".repeat(20_000) + BREAK + "THE END");
        this.client.replies.add("{}");

        run();

        final String asked = this.client.asked.get(0);
        assertTrue(asked.contains("THE END"), "the end is sent");
        assertTrue(asked.contains(CallBudget.OMISSION_MARKER), "and the gap is marked");
        assertFalse(asked.contains("x".repeat(20_000)), "but not the whole middle");
    }

    // A step that names extra instructions gets them in front of the intake prompt, so domain knowledge
    // that is only worth sending for one schema is not added to every reading
    @Test
    void prependsExtraInstructionsToTheIntakePrompt() throws IOException
    {
        addMarkdown(DOCUMENT);
        this.client.replies.add(answer("Yes", 0.9, quote(REAL_QUOTE)));

        this.intake.run(file(), scan(), List.of(aims()), "# Extra\n\nOnly this reading.");

        assertTrue(this.client.system.contains("Only this reading."), this.client.system);
        assertTrue(this.client.system.contains("intake extraction engine"), this.client.system);
        assertTrue(this.client.system.indexOf("Only this reading.")
            < this.client.system.indexOf("intake extraction engine"), this.client.system);
    }

    @Test
    void readsNothingWhenThePromptLeavesNoRoomForTheDocumentAtAll() throws IOException
    {
        this.window = 1;
        addMarkdown(DOCUMENT);

        assertTrue(run().degraded(), "asking every field over no document could only invent");
        assertTrue(this.client.asked.isEmpty(), "and the call is not made at all");
    }
}
