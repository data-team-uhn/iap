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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.extraction.internal.AnswerIntakeService.ChunkTagging;
import io.uhndata.iap.extraction.internal.AnswerIntakeService.IntakeResult;
import io.uhndata.iap.llm.LLMClient;
import io.uhndata.iap.llm.LLMClientFactory;
import io.uhndata.iap.llm.LLMConfigurationService;
import io.uhndata.iap.llm.LLMMessage;
import io.uhndata.iap.llm.LLMRequestOptions;
import io.uhndata.iap.llm.LLMSettings;
import io.uhndata.iap.submissions.models.File;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AnswerIntakeService}: what it asks, how it holds the answer to the evidence, and how
 * the tags it is given end up on the chunks.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class AnswerIntakeServiceTest
{
    private static final String FILE_PATH = "/Submissions/s1/proposal/v1/file";

    private static final String AIMS_TEXT = "## Aims\n\n<!-- page: 2 -->\nThe primary aim is to reduce readmissions.\n";

    private static final String BUDGET_TEXT = "## Budget\n\nThe budget is large.\n";

    private static final String AIMS = "aims";

    private static final String CHUNK_1 = "Chunk-1";

    private static final String CHUNK_2 = "Chunk-2";

    private static final String FOUND_AIMS = """
        {"aims": {"found_answer": true, "confidence": 0.9, "value": "reduce readmissions",
                  "reasoning": "Stated under Aims.",
                  "evidence": [{"quote": "The primary aim is to reduce readmissions", "chunk_id": "Chunk-1",
                                "page": 2}]},
         "chunk_tags": [{"chunk_id": "Chunk-1", "tags": ["B.3", "B.4", "B.5"], "confidence": 0.8},
                        {"chunk_id": "Chunk-2", "tags": ["B.9"], "confidence": 0.7},
                        {"chunk_id": "Chunk-9", "tags": ["B.1"], "confidence": 0.7}]}
        """;

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private AnswerIntakeService intake;

    private StubClient client;

    private IOException settingsFailure;

    private long budget = 1_000_000L;

    /**
     * Answers with whatever the test lined up, and remembers what it was asked.
     */
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
        public String chat(final String userMessage)
        {
            return next();
        }

        @Override
        public String chat(final String systemPrompt, final String userMessage)
        {
            return next();
        }

        @Override
        public String chat(final String systemPrompt, final List<LLMMessage> messages)
        {
            return next();
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

    /**
     * A tree nothing may be written to, standing in for one the caller has no write access to.
     */
    private static final class ReadOnly extends ResourceWrapper
    {
        ReadOnly(final Resource wrapped)
        {
            super(wrapped);
        }

        @Override
        public Resource getChild(final String relPath)
        {
            final Resource child = super.getChild(relPath);
            return child == null ? null : new ReadOnly(child);
        }

        @Override
        public <T> T adaptTo(final Class<T> type)
        {
            return ModifiableValueMap.class.equals(type) ? null : super.adaptTo(type);
        }
    }

    @BeforeEach
    void setUp() throws Exception
    {
        this.intake = new AnswerIntakeService();
        this.client = new StubClient();
        inject("llmClientFactory", new LLMClientFactory()
        {
            @Override
            public LLMClient getClient(final String providerApi)
            {
                return AnswerIntakeServiceTest.this.client;
            }

            @Override
            public LLMClient getActiveClient()
            {
                return AnswerIntakeServiceTest.this.client;
            }
        });
        inject("configurationService", (LLMConfigurationService) () -> {
            if (this.settingsFailure != null) {
                throw this.settingsFailure;
            }
            return new LLMSettings("local", new LLMSettings.ProviderSettings(null, null, 0, null), "m",
                new LLMSettings.ModelSettings(0, 0, 0.0, 0, this.budget, null, null));
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

    private Resource fileResource()
    {
        return this.context.resourceResolver().getResource(FILE_PATH);
    }

    private void addChunk(final String name, final String text, final Map<String, Object> tagging)
    {
        final String chunksPath = FILE_PATH + "/chunks";
        if (this.context.resourceResolver().getResource(chunksPath) == null) {
            this.context.create().resource(chunksPath, Map.of("sling:resourceType", "sub/Chunks"));
        }
        final Map<String, Object> properties = new HashMap<>(tagging);
        properties.put("sling:resourceType", "sub/Chunk");
        final Resource chunk = this.context.create().resource(chunksPath + "/" + name, properties);
        storeText(chunk.getPath() + "/content", text);
    }

    private void addMarkdown(final String text)
    {
        storeText(FILE_PATH + "/markdownFile", text);
    }

    private void storeText(final String path, final String text)
    {
        final Resource file = this.context.create().resource(path, Map.of("jcr:primaryType", "nt:file"));
        this.context.create().resource(file.getPath() + "/jcr:content", Map.of(
            "jcr:primaryType", "nt:resource",
            "jcr:data", new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))));
    }

    private void addTwoChunks()
    {
        addChunk(CHUNK_1, AIMS_TEXT, Map.of("rubricTags", new String[]{ "B.3" }, "tagConfidence", 0.4));
        addChunk(CHUNK_2, BUDGET_TEXT, Map.of());
    }

    private static ExtractionField aims()
    {
        return new ExtractionField(AIMS, "What are the aims?", "Whether there is one", "Find the aims.", null,
            List.of("B.3"), false);
    }

    private ValueMap chunkProperties(final String name)
    {
        return this.context.resourceResolver().getResource(FILE_PATH + "/chunks/" + name).getValueMap();
    }

    @Test
    void asksNothingWhenThereIsNothingToExtract() throws IOException
    {
        addTwoChunks();

        final IntakeResult result = this.intake.run(file(), List.of());

        assertFalse(result.degraded());
        assertTrue(result.fields().isEmpty());
        assertTrue(this.client.asked.isEmpty());
    }

    @Test
    void readsAFieldTheModelFoundAndBackedWithAQuote() throws IOException
    {
        addTwoChunks();
        this.client.replies.add(FOUND_AIMS);

        final FieldResult result = this.intake.run(file(), List.of(aims())).fields().get(AIMS);

        assertTrue(result.found());
        assertEquals("reduce readmissions", result.value());
        assertEquals(0.9, result.confidence(), "a verified quote keeps the model's confidence");
        assertEquals("Stated under Aims.", result.reasoning());
        assertEquals(1, result.passages().size());
        assertEquals(CHUNK_1, result.passages().get(0).chunkId());
        assertEquals(2L, result.passages().get(0).page());
    }

    @Test
    void discountsAnAnswerNoneOfWhoseQuotesCanBeFound() throws IOException
    {
        addTwoChunks();
        this.client.replies.add(FOUND_AIMS.replace("The primary aim is to reduce readmissions",
            "The secondary aim is to increase readmissions"));

        final FieldResult result = this.intake.run(file(), List.of(aims())).fields().get(AIMS);

        assertTrue(result.found());
        assertEquals(0.9 * AnswerIntakeService.UNVERIFIED_PENALTY, result.confidence(), 1e-9);
    }

    // A quote nobody can find is not evidence, so it is dropped rather than shown to a reviewer who
    // would read a sentence that is not in their document
    @Test
    void keepsOnlyTheQuotesItCouldFind() throws IOException
    {
        addTwoChunks();
        this.client.replies.add("""
            {"aims": {"found_answer": true, "confidence": 1.0, "value": "reduce readmissions",
                      "reasoning": "Stated under Aims.",
                      "evidence": [{"quote": "The primary aim is to reduce readmissions", "chunk_id": "Chunk-1"},
                                   {"quote": "Participants were randomised to placebo", "chunk_id": "Chunk-1"}]},
             "chunk_tags": []}
            """);

        final FieldResult result = this.intake.run(file(), List.of(aims())).fields().get(AIMS);

        assertEquals(1, result.passages().size(), "the invented one is gone");
        assertEquals("The primary aim is to reduce readmissions", result.passages().get(0).quote());
    }

    // Half the evidence holding up is a worse answer than all of it and a better one than none, and a flat
    // penalty cannot say so
    @Test
    void discountsAnAnswerByHowMuchOfItsEvidenceHeldUp() throws IOException
    {
        addTwoChunks();
        this.client.replies.add("""
            {"aims": {"found_answer": true, "confidence": 1.0, "value": "reduce readmissions",
                      "reasoning": "Stated under Aims.",
                      "evidence": [{"quote": "The primary aim is to reduce readmissions", "chunk_id": "Chunk-1"},
                                   {"quote": "Participants were randomised to placebo", "chunk_id": "Chunk-1"}]},
             "chunk_tags": []}
            """);

        final FieldResult result = this.intake.run(file(), List.of(aims())).fields().get(AIMS);

        assertEquals(0.75, result.confidence(), 1e-9, "half the quotes held up, so half the way to the floor");
    }

    // A quote nobody can find is the strongest signal there is that the answer needs reading again
    @Test
    void dropsEveryQuoteWhenNoneOfThemIsThere() throws IOException
    {
        addTwoChunks();
        this.client.replies.add(FOUND_AIMS.replace("The primary aim is to reduce readmissions",
            "Participants were randomised to placebo"));

        final FieldResult result = this.intake.run(file(), List.of(aims())).fields().get(AIMS);

        assertTrue(result.passages().isEmpty());
        assertTrue(result.found(), "the answer may still be right; only its evidence was not");
    }

    @Test
    void sendsAFieldWithAQuoteThatCannotBeFoundToASecondLook() throws IOException
    {
        addTwoChunks();
        this.client.replies.add(FOUND_AIMS.replace("The primary aim is to reduce readmissions",
            "Participants were randomised to placebo"));

        assertTrue(this.intake.run(file(), List.of(aims())).fields().get(AIMS).needsSecondLook());
    }

    @Test
    void sendsAnAnswerNobodyIsSureOfToASecondLook() throws IOException
    {
        addTwoChunks();
        this.client.replies.add(FOUND_AIMS.replace("\"confidence\": 0.9", "\"confidence\": 0.5"));

        assertTrue(this.intake.run(file(), List.of(aims())).fields().get(AIMS).needsSecondLook());
    }

    @Test
    void sendsAFieldNothingWasFoundForToASecondLook() throws IOException
    {
        addTwoChunks();
        this.client.replies.add("{\"aims\": {\"found_answer\": false, \"confidence\": 0.9, \"value\": null,"
            + " \"reasoning\": \"Not stated.\", \"evidence\": []}, \"chunk_tags\": []}");

        assertTrue(this.intake.run(file(), List.of(aims())).fields().get(AIMS).needsSecondLook());
    }

    @Test
    void leavesAWellEvidencedConfidentAnswerAlone() throws IOException
    {
        addTwoChunks();
        this.client.replies.add(FOUND_AIMS.replace("\"confidence\": 0.9", "\"confidence\": 1.0"));

        final FieldResult result = this.intake.run(file(), List.of(aims())).fields().get(AIMS);

        assertEquals(1.0, result.confidence(), 1e-9, "every quote held up, so nothing is taken off");
        assertFalse(result.needsSecondLook());
    }

    // A model told only that its answer was unreadable has nothing to correct
    @Test
    void tellsTheModelWhatWasActuallyWrongWhenItAsksAgain() throws IOException
    {
        addTwoChunks();
        this.client.replies.add("{\"aims\": {\"found_answer\": true,");
        this.client.replies.add(FOUND_AIMS);

        this.intake.run(file(), List.of(aims()));

        assertTrue(this.client.asked.get(1).contains("never closed"), this.client.asked.get(1));
        assertTrue(this.client.asked.get(1).contains("cut off"), "and what that means for the answer");
    }

    @Test
    void tellsTheModelWhenItSaidNothingAtAll() throws IOException
    {
        addTwoChunks();
        this.client.replies.add("");
        this.client.replies.add(FOUND_AIMS);

        this.intake.run(file(), List.of(aims()));

        assertTrue(this.client.asked.get(1).contains("it was empty"), this.client.asked.get(1));
    }

    @Test
    void tellsTheModelWhenItAnsweredInProseInstead() throws IOException
    {
        addTwoChunks();
        this.client.replies.add("I could not find the aims in this document, sorry.");
        this.client.replies.add(FOUND_AIMS);

        this.intake.run(file(), List.of(aims()));

        assertTrue(this.client.asked.get(1).contains("no JSON object"), this.client.asked.get(1));
    }

    // The chunks were read and paid for, so a later pass has to know not to read them again
    @Test
    void stillRecordsWhatADegradedCallRead() throws IOException
    {
        addTwoChunks();
        this.client.replies.add("not json");
        this.client.replies.add("still not json");

        assertEquals(List.of(CHUNK_1, CHUNK_2), this.intake.run(file(), List.of(aims())).sentChunkIds());
    }

    @Test
    void writesNoTagsFromADegradedCall() throws IOException, PersistenceException
    {
        addTwoChunks();
        this.client.replies.add("not json");
        this.client.replies.add("still not json");

        final IntakeResult result = this.intake.run(file(), List.of(aims()));
        this.intake.applyTags(fileResource(), result);

        assertArrayEquals(new String[]{ "B.3" }, chunkProperties(CHUNK_1).get("rubricTags", String[].class),
            "what the gate wrote is left exactly as it was");
    }

    // Step 2 names the chunks itself, because its planner has worked out what this field has not seen
    @Test
    void asksOverOnlyTheChunksItWasGiven() throws IOException
    {
        addTwoChunks();
        this.client.replies.add(FOUND_AIMS);

        final IntakeResult result =
            this.intake.runOver(file(), List.of(aims()), Set.of(CHUNK_2));

        assertEquals(List.of(CHUNK_2), result.sentChunkIds());
        assertTrue(this.client.asked.get(0).contains(BUDGET_TEXT.strip()));
        assertFalse(this.client.asked.get(0).contains(AIMS_TEXT.strip()), "Chunk-1 was not asked for");
    }

    @Test
    void asksNothingWhenStepTwoNamesNoFields() throws IOException
    {
        addTwoChunks();

        assertTrue(this.intake.runOver(file(), List.of(), Set.of(CHUNK_1)).fields().isEmpty());
        assertTrue(this.client.asked.isEmpty());
    }

    @Test
    void asksNothingWhenStepTwoNamesNoChunks() throws IOException
    {
        addTwoChunks();

        assertTrue(this.intake.runOver(file(), List.of(aims()), Set.of()).fields().isEmpty());
        assertTrue(this.client.asked.isEmpty());
    }

    // The first pass already sent the whole thing
    @Test
    void asksNothingOverADocumentThatWasNeverChunked() throws IOException
    {
        addMarkdown("# A proposal\n\nThe primary aim is to reduce readmissions.\n");

        assertTrue(this.intake.runOver(file(), List.of(aims()), Set.of(CHUNK_1)).fields().isEmpty());
        assertTrue(this.client.asked.isEmpty());
    }

    @Test
    void degradesWhenAStepTwoCallCannotBeRead() throws IOException
    {
        addTwoChunks();
        this.client.replies.add("not json");
        this.client.replies.add("still not json");

        final IntakeResult result =
            this.intake.runOver(file(), List.of(aims()), Set.of(CHUNK_1));

        assertTrue(result.degraded());
        assertEquals(List.of(CHUNK_1), result.sentChunkIds(), "what was read is still recorded");
    }

    @Test
    void findsTheChunkAQuoteReallyCameFromWhenTheModelNamedTheWrongOne() throws IOException
    {
        addTwoChunks();
        this.client.replies.add(FOUND_AIMS.replace("\"chunk_id\": \"Chunk-1\",\n", "\"chunk_id\": \"Chunk-9\",\n"));

        final FieldResult result = this.intake.run(file(), List.of(aims())).fields().get(AIMS);

        // The quote really is in the document, so only its label was wrong. Discounting the answer for that
        // would punish a good quote for a bad name.
        assertEquals(CHUNK_1, result.passages().get(0).chunkId());
        assertEquals(0.9, result.confidence(), 1e-9);
    }

    @Test
    void stillDiscountsAQuoteThatIsInNoChunkAtAll() throws IOException
    {
        addTwoChunks();
        this.client.replies.add(FOUND_AIMS.replace("The primary aim is to reduce readmissions",
            "Participants were randomised to placebo"));

        assertEquals(0.9 * AnswerIntakeService.UNVERIFIED_PENALTY,
            this.intake.run(file(), List.of(aims())).fields().get(AIMS).confidence(), 1e-9);
    }

    @Test
    void anAnswerWithNoValueIsNotAnAnswer() throws IOException
    {
        addTwoChunks();
        this.client.replies.add("{\"aims\": {\"found_answer\": true, \"confidence\": 0.9, \"value\": null,"
            + " \"reasoning\": \"Sure of it.\", \"evidence\": []}, \"chunk_tags\": []}");

        final FieldResult result = this.intake.run(file(), List.of(aims())).fields().get(AIMS);

        assertFalse(result.found());
        assertNull(result.value());
    }

    @Test
    void aFieldTheModelLeftOutIsNotFound() throws IOException
    {
        addTwoChunks();
        this.client.replies.add("{\"chunk_tags\": []}");

        final FieldResult result = this.intake.run(file(), List.of(aims())).fields().get(AIMS);

        assertFalse(result.found());
        assertEquals(0.0, result.confidence());
        assertEquals("", result.reasoning());
        assertTrue(result.passages().isEmpty());
    }

    @Test
    void keepsOnlyTheEvidenceItCanUse() throws IOException
    {
        addTwoChunks();
        this.client.replies.add("{\"aims\": {\"found_answer\": true, \"confidence\": 0.5, \"value\": \"v\","
            + " \"evidence\": [\"not an object\", {\"quote\": \"  \", \"chunk_id\": \"Chunk-1\", \"page\": 1},"
            + " {\"quote\": \"The primary aim is to reduce readmissions\", \"chunk_id\": \"Chunk-1\", \"page\": null},"
            + " {\"quote\": \"readmissions\", \"page\": 2}]}, \"chunk_tags\": []}");

        final FieldResult result = this.intake.run(file(), List.of(aims())).fields().get(AIMS);

        assertEquals(2, result.passages().size());
        assertNull(result.passages().get(0).page(), "a document with no page markers has no page");
        // The model named no chunk for this one, and the quote is short enough to be found in Chunk-1
        assertEquals(CHUNK_1, result.passages().get(1).chunkId(),
            "a quote the model did not place is placed by looking for it");
        assertEquals("", result.reasoning(), "reasoning the model left out reads as empty");
    }

    @Test
    void copesWithEvidenceThatIsNotAList() throws IOException
    {
        addTwoChunks();
        this.client.replies.add("{\"aims\": {\"found_answer\": true, \"confidence\": 0.5, \"value\": \"v\","
            + " \"reasoning\": \"r\", \"evidence\": \"none\"}, \"chunk_tags\": []}");

        assertTrue(this.intake.run(file(), List.of(aims())).fields().get(AIMS).passages().isEmpty());
    }

    @Test
    void readsTheTagsForTheChunksItSentAndNoOthers() throws IOException
    {
        addTwoChunks();
        this.client.replies.add(FOUND_AIMS);

        final List<ChunkTagging> tags = this.intake.run(file(), List.of(aims())).chunkTags();

        assertEquals(2, tags.size(), "Chunk-9 was never sent, so its tag is the model tagging what it did not read");
        assertEquals(CHUNK_1, tags.get(0).chunkId());
        assertEquals(List.of("B.3", "B.4", "B.5"), tags.get(0).tags());
        assertEquals(0.8, tags.get(0).confidence());
        assertEquals(0.7, tags.get(1).confidence());
    }

    @Test
    void copesWithTagsThatAreMissingOrMalformed() throws IOException
    {
        addTwoChunks();
        this.client.replies.add("{\"aims\": {\"found_answer\": false, \"confidence\": 0.6, \"value\": null,"
            + " \"reasoning\": \"Not here.\", \"evidence\": []}, \"chunk_tags\": [\"loose\", {\"tags\": [\"B.1\"]}]}");

        assertTrue(this.intake.run(file(), List.of(aims())).chunkTags().isEmpty());

        this.client.replies.add("{\"aims\": {\"found_answer\": false, \"confidence\": 0.6, \"value\": null,"
            + " \"reasoning\": \"Not here.\", \"evidence\": []}}");

        assertTrue(this.intake.run(file(), List.of(aims())).chunkTags().isEmpty());
    }

    @Test
    void showsTheModelTheGlossaryThenTheInstructionsThenTheDocument() throws IOException
    {
        addTwoChunks();
        this.client.replies.add(FOUND_AIMS);

        this.intake.run(file(), List.of(aims()));

        assertTrue(this.client.system.startsWith("## PROTOCOL_STRUCTURE_GLOSSARY\n\n"),
            "the constant part goes first, so every call shares a cached prefix");
        assertTrue(this.client.system.indexOf("B.1:") < this.client.system.indexOf("# Role"),
            "the glossary comes before the instructions");
        final String asked = this.client.asked.get(0);
        assertTrue(asked.contains("## SCHEMA\n\naims:\nquestion: What are the aims?"));
        assertTrue(asked.contains("## CATALOG"));
        assertTrue(asked.contains("[chunk:Chunk-1]\n## Aims"));
        assertEquals("iap_intake", this.client.options.get(0).getResponseSchemaName());
        assertTrue(this.client.options.get(0).getResponseSchema().contains("\"aims\""));
    }

    @Test
    void asksAgainWhenTheAnswerIsNotTheShapeItHadToBe() throws IOException
    {
        addTwoChunks();
        this.client.replies.add("I would rather not.");
        this.client.replies.add(FOUND_AIMS);

        final IntakeResult result = this.intake.run(file(), List.of(aims()));

        assertFalse(result.degraded());
        assertTrue(result.fields().get(AIMS).found());
        assertEquals(2, this.client.asked.size());
        assertTrue(this.client.asked.get(1).endsWith("nothing else."), "the second ask carries the correction");
    }

    @Test
    void givesUpAfterTwoUnreadableAnswersWithoutGuessing() throws IOException
    {
        addTwoChunks();
        this.client.replies.add("no");
        this.client.replies.add("still no");

        final IntakeResult result = this.intake.run(file(), List.of(aims()));

        assertTrue(result.degraded());
        assertTrue(result.fields().isEmpty(), "nothing is settled by guesswork");
        assertTrue(result.chunkTags().isEmpty());
        assertEquals(List.of(CHUNK_1, CHUNK_2), result.sentChunkIds(), "what was read is still recorded");
    }

    @Test
    void readsAWholeDocumentThatWasNeverSplitUp() throws IOException
    {
        addMarkdown("# Proposal\n\n## Aims\n\nThe primary aim is to reduce readmissions.\n");
        this.client.replies.add(FOUND_AIMS.replace("\"chunk_id\": \"Chunk-1\",\n", "\"chunk_id\": \"document\",\n"));

        final IntakeResult result = this.intake.run(file(), List.of(aims()));

        assertEquals(List.of(IntakePayload.WHOLE_DOCUMENT_ID), result.sentChunkIds());
        assertEquals(0.9, result.fields().get(AIMS).confidence(), "the quote is found in the whole document");
        assertFalse(this.client.asked.get(0).contains("## CATALOG"));
    }

    @Test
    void hasNothingToAskAboutADocumentWithNoText() throws IOException
    {
        final IntakeResult result = this.intake.run(file(), List.of(aims()));

        assertTrue(result.degraded());
        assertTrue(result.sentChunkIds().isEmpty());
        assertTrue(this.client.asked.isEmpty());
    }

    @Test
    void letsAnUnreachableModelBeReportedRatherThanHidden()
    {
        addTwoChunks();
        this.client.failure = new IOException("the model is unreachable");

        final IOException failure = assertThrows(IOException.class, () -> this.intake.run(file(), List.of(aims())));
        assertEquals("the model is unreachable", failure.getMessage());
    }

    @Test
    void fallsBackToTheDefaultBudgetWhenTheSettingsCannotBeRead() throws IOException
    {
        addTwoChunks();
        this.settingsFailure = new IOException("no configuration");
        this.client.replies.add(FOUND_AIMS);

        assertFalse(this.intake.run(file(), List.of(aims())).degraded());
        assertEquals(2, this.client.asked.get(0).split("\\[chunk:").length - 1, "both chunks fit the default");
    }

    @Test
    void writesTheTagsForTheChunksTheModelRead() throws IOException, PersistenceException
    {
        addTwoChunks();
        this.client.replies.add(FOUND_AIMS);

        this.intake.applyTags(fileResource(), this.intake.run(file(), List.of(aims())));

        assertArrayEquals(new String[]{ "B.3", "B.4", "B.5" },
            chunkProperties(CHUNK_1).get("rubricTags", String[].class),
            "every tag the model gave is kept: a chunk really can be several things at once");
        assertEquals(0.8, chunkProperties(CHUNK_1).get("tagConfidence", Double.class),
            "the gate's 0.4 is replaced: the model has now actually read the chunk");
        assertArrayEquals(new String[]{ "B.9" }, chunkProperties(CHUNK_2).get("rubricTags", String[].class));
        assertEquals(0.7, chunkProperties(CHUNK_2).get("tagConfidence", Double.class));
    }

    @Test
    void leavesAChunkAloneRatherThanGuessingWhenTheTagsAreNotReal() throws PersistenceException
    {
        addTwoChunks();
        final IntakeResult result = new IntakeResult(Map.of(),
            List.of(new ChunkTagging(CHUNK_1, List.of("B.99", "nonsense", "b.3"), 0.9)),
            List.of(CHUNK_1), false);

        this.intake.applyTags(fileResource(), result);

        assertArrayEquals(new String[]{ "B.3" }, chunkProperties(CHUNK_1).get("rubricTags", String[].class),
            "what the gate wrote stays: its placement beats none, and clearing it would make the chunk a "
                + "wildcard, which is right only for a chunk nothing has ever placed");
        assertEquals(0.4, chunkProperties(CHUNK_1).get("tagConfidence", Double.class));
    }

    @Test
    void dropsDuplicateTags() throws PersistenceException
    {
        addTwoChunks();
        final IntakeResult result = new IntakeResult(Map.of(),
            List.of(new ChunkTagging(CHUNK_2, List.of("B.5", " B.5 ", "B.6"), 0.9)), List.of(CHUNK_2), false);

        this.intake.applyTags(fileResource(), result);

        assertArrayEquals(new String[]{ "B.5", "B.6" }, chunkProperties(CHUNK_2).get("rubricTags", String[].class));
    }

    @Test
    void skipsATagForAChunkTheDocumentDoesNotHave() throws PersistenceException
    {
        addTwoChunks();
        final IntakeResult result = new IntakeResult(Map.of(),
            List.of(new ChunkTagging("Chunk-9", List.of("B.1"), 0.9)), List.of(), false);

        this.intake.applyTags(fileResource(), result);

        assertNull(chunkProperties(CHUNK_2).get("tagConfidence", Double.class), "nothing else was touched");
    }

    @Test
    void writesNothingWhenThereIsNothingToWrite() throws PersistenceException
    {
        this.intake.applyTags(fileResource(), IntakeResult.degraded(List.of()));

        addTwoChunks();
        this.intake.applyTags(fileResource(), IntakeResult.degraded(List.of(CHUNK_1)));

        assertNull(chunkProperties(CHUNK_2).get("tagConfidence", Double.class));
    }

    @Test
    void refusesToTagWhatItMayNotWrite()
    {
        addTwoChunks();
        final IntakeResult result = new IntakeResult(Map.of(),
            List.of(new ChunkTagging(CHUNK_1, List.of("B.3"), 0.9)), List.of(CHUNK_1), false);

        assertThrows(PersistenceException.class,
            () -> this.intake.applyTags(new ReadOnly(fileResource()), result));
    }
}
