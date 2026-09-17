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

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.extraction.internal.ProposalGateService.GateDecision;
import io.uhndata.iap.extraction.internal.ProposalGateService.Verdict;
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
 * Unit tests for {@link ProposalCategoryService}: when a proposal gets a second look, what the model is shown
 * for it, and how its answer is held to the categories that exist.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ProposalCategoryServiceTest
{
    private static final String FILE_PATH = "/Submissions/s1/proposal/v1/file";

    private static final String CHUNK_1 = "Chunk-1";

    private static final String CHUNK_2 = "Chunk-2";

    private static final String CHUNK_3 = "Chunk-3";

    private static final String DATA = "/Categories/Retrospective/Data";

    private static final String TRIALS = "/Categories/Prospective/Trials";

    private static final List<CategoryCatalog.Entry> CATEGORIES = List.of(
        new CategoryCatalog.Entry(DATA, "Retrospective Data Studies", "Chart reviews."),
        new CategoryCatalog.Entry(TRIALS, "Clinical trials", "Experimental products."));

    private static final String PICKS_DATA = "{\"category\": \"" + DATA + "\", \"confidence\": 0.9,"
        + " \"reasoning\": \"A chart review.\"}";

    private static final String CHART_REVIEW = "# A small proposal\n\nA chart review of existing records.\n";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private ProposalCategoryService service;

    private StubClient client;

    private long budget = 1_000_000L;

    private boolean settingsUnreadable;

    /**
     * Answers with whatever the test lined up, and remembers what it was asked.
     */
    private static final class StubClient implements LLMClient
    {
        private final List<String> replies = new ArrayList<>();

        private final List<String> asked = new ArrayList<>();

        private final List<String> told = new ArrayList<>();

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
            final LLMRequestOptions options) throws IOException
        {
            if (this.failure != null) {
                throw this.failure;
            }
            this.asked.add(messages.get(0).getContent());
            this.told.add(systemPrompt);
            return next();
        }
    }

    @BeforeEach
    void setUp() throws Exception
    {
        this.service = new ProposalCategoryService();
        this.client = new StubClient();
        inject("llmClientFactory", new LLMClientFactory()
        {
            @Override
            public LLMClient getClient(final String providerApi)
            {
                return ProposalCategoryServiceTest.this.client;
            }

            @Override
            public LLMClient getActiveClient()
            {
                return ProposalCategoryServiceTest.this.client;
            }
        });
        inject("configurationService", (LLMConfigurationService) () -> {
            if (this.settingsUnreadable) {
                throw new IOException("no settings");
            }
            return new LLMSettings("local", new LLMSettings.ProviderSettings(null, null, 0, null), "m",
                new LLMSettings.ModelSettings(0, 0, 0.0, 0, this.budget, null, null));
        });
    }

    private void inject(final String name, final Object value) throws Exception
    {
        final Field field = ProposalCategoryService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(this.service, value);
    }

    private File fileWith(final Map<String, Object> properties)
    {
        final Map<String, Object> all = new HashMap<>(properties);
        all.put("sling:resourceType", File.RESOURCE_TYPE);
        final Resource resource = this.context.create().resource(FILE_PATH, all);
        final File model = resource.adaptTo(File.class);
        assertNotNull(model);
        return model;
    }

    /** A chunk; with tags, one a model read and placed, so selection can rule it out. */
    private void addChunk(final String name, final String text, final String... tags)
    {
        final String chunksPath = FILE_PATH + "/chunks";
        if (this.context.resourceResolver().getResource(chunksPath) == null) {
            this.context.create().resource(chunksPath, Map.of("sling:resourceType", "sub/Chunks"));
        }
        final Map<String, Object> properties = new HashMap<>();
        properties.put("sling:resourceType", "sub/Chunk");
        if (tags.length > 0) {
            properties.put("tagBasis", "fulltext");
            properties.put("rubricTags", tags);
        }
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

    private static String text(final int tokens)
    {
        return "x".repeat(tokens * ChunkContent.CHARS_PER_TOKEN);
    }

    private String lastAsked()
    {
        return this.client.asked.get(this.client.asked.size() - 1);
    }

    private String lastTold()
    {
        return this.client.told.get(this.client.told.size() - 1);
    }

    private static GateDecision decision(final Verdict verdict, final CategoryPick pick)
    {
        return new GateDecision(verdict, 0.9, "", List.of(), pick);
    }

    @Test
    void needsNoSecondLookForADocumentThatIsNotAProposal()
    {
        assertFalse(this.service.needsSecondLook(decision(Verdict.NOT_PROPOSAL, null)));
        assertFalse(this.service.needsSecondLook(decision(Verdict.UNDETERMINED, null)));
    }

    @Test
    void needsASecondLookWhenTheGatePickedNoCategory()
    {
        assertTrue(this.service.needsSecondLook(decision(Verdict.PROPOSAL, null)));
    }

    @Test
    void needsASecondLookWhenTheGateWasNotSureEnough()
    {
        assertTrue(this.service.needsSecondLook(decision(Verdict.PROPOSAL, new CategoryPick(DATA, 0.6))));
        assertFalse(this.service.needsSecondLook(decision(Verdict.PROPOSAL, new CategoryPick(DATA, 0.75))),
            "the floor itself is sure enough");
    }

    @Test
    void picksNothingWhenThereAreNoCategories() throws IOException
    {
        final File file = fileWith(Map.of());
        addMarkdown(CHART_REVIEW);

        assertNull(this.service.classify(file, List.of()));
        assertTrue(this.client.asked.isEmpty(), "there is nothing to ask");
    }

    @Test
    void readsTheWholeDocumentWhenItWasNeverSplitUp() throws IOException
    {
        final File file = fileWith(Map.of());
        addMarkdown(CHART_REVIEW);
        this.client.replies.add(PICKS_DATA);

        final CategoryPick pick = this.service.classify(file, CATEGORIES);

        assertNotNull(pick);
        assertEquals(DATA, pick.path());
        assertEquals(0.9, pick.confidence());
        assertTrue(lastAsked().contains("## CHUNK (untrusted data)\n\n[chunk:document]\n# A small proposal"));
        assertFalse(lastAsked().contains("## CATALOG"), "there is no outline to show");
    }

    @Test
    void cutsAWholeDocumentToTheBudget() throws IOException
    {
        this.budget = 2;
        final File file = fileWith(Map.of());
        addMarkdown("abcdefghijkl");
        this.client.replies.add(PICKS_DATA);

        this.service.classify(file, CATEGORIES);

        assertTrue(lastAsked().endsWith("[chunk:document]\nabcdefgh"), lastAsked());
    }

    @Test
    void readsThePartsThatSayWhatTheStudyDoes() throws IOException
    {
        this.budget = 100;
        final File file = fileWith(Map.of("chunked", true));
        addChunk(CHUNK_1, "## Background\n\n" + text(40));
        addChunk(CHUNK_2, "## Budget\n\n" + text(40), "B.9");
        addChunk(CHUNK_3, "## Design\n\n" + text(40), "B.3");
        this.client.replies.add(PICKS_DATA);

        this.service.classify(file, CATEGORIES);

        assertTrue(lastAsked().contains("[chunk:Chunk-1]\n## Background"), "an unplaced chunk goes");
        assertTrue(lastAsked().contains("[chunk:Chunk-3]\n## Design"), "a design chunk goes");
        assertFalse(lastAsked().contains("[chunk:Chunk-2]"), "a chunk placed elsewhere stays out");
        assertTrue(lastAsked().contains("Chunk-1: Background\nChunk-2: Budget\nChunk-3: Design"),
            "but the outline lists everything");
    }

    @Test
    void readsEverythingWhenItAllFits() throws IOException
    {
        final File file = fileWith(Map.of("chunked", true));
        addChunk(CHUNK_1, "## Background\n\n" + text(40));
        addChunk(CHUNK_2, "## Budget\n\n" + text(40), "B.9");
        this.client.replies.add(PICKS_DATA);

        this.service.classify(file, CATEGORIES);

        assertTrue(lastAsked().contains("[chunk:Chunk-2]"));
    }

    @Test
    void picksNothingWhenThereIsNothingToShow() throws IOException
    {
        final File file = fileWith(Map.of("chunked", true));
        addChunk(CHUNK_1, "   ");

        assertNull(this.service.classify(file, CATEGORIES));
        assertTrue(this.client.asked.isEmpty());
    }

    @Test
    void picksNothingForADocumentWithNoTextAtAll() throws IOException
    {
        final File file = fileWith(Map.of());

        assertNull(this.service.classify(file, CATEGORIES));
        assertTrue(this.client.asked.isEmpty());
    }

    @Test
    void acceptsALabelInPlaceOfAnId() throws IOException
    {
        final File file = fileWith(Map.of());
        addMarkdown(CHART_REVIEW);
        this.client.replies.add("{\"category\": \"clinical TRIALS\", \"confidence\": 0.7, \"reasoning\": \"\"}");

        final CategoryPick pick = this.service.classify(file, CATEGORIES);

        assertNotNull(pick);
        assertEquals(TRIALS, pick.path());
    }

    @Test
    void picksNothingWhenTheAnswerNamesACategoryThatDoesNotExist() throws IOException
    {
        final File file = fileWith(Map.of());
        addMarkdown(CHART_REVIEW);
        this.client.replies.add("{\"category\": \"/Categories/Other\", \"confidence\": 0.9, \"reasoning\": \"\"}");

        assertNull(this.service.classify(file, CATEGORIES), "not a guess at the nearest one either");
        assertEquals(1, this.client.asked.size(), "the answer was readable, so it is not asked again");
    }

    @Test
    void picksNothingWhenTheModelSaysNoCategoryFits() throws IOException
    {
        final File file = fileWith(Map.of());
        addMarkdown(CHART_REVIEW);
        this.client.replies.add("{\"category\": null, \"confidence\": 0, \"reasoning\": \"Too little to go on.\"}");

        assertNull(this.service.classify(file, CATEGORIES));
        assertEquals(1, this.client.asked.size());
    }

    @Test
    void asksAgainWhenTheAnswerIsNotTheShapeItHadToBe() throws IOException
    {
        final File file = fileWith(Map.of());
        addMarkdown(CHART_REVIEW);
        this.client.replies.add("I think it is a chart review.");
        this.client.replies.add(PICKS_DATA);

        final CategoryPick pick = this.service.classify(file, CATEGORIES);

        assertNotNull(pick);
        assertEquals(DATA, pick.path());
        assertEquals(2, this.client.asked.size());
        assertTrue(lastAsked().contains("# Correction"), "the second time it is told what went wrong");
    }

    @Test
    void picksNothingWhenTwoAnswersAreBothUnusable() throws IOException
    {
        final File file = fileWith(Map.of());
        addMarkdown(CHART_REVIEW);
        this.client.replies.add("");
        this.client.replies.add("{\"confidence\": 0.9}");

        assertNull(this.service.classify(file, CATEGORIES));
        assertEquals(2, this.client.asked.size());
    }

    @Test
    void picksNothingWhenTheModelCannotBeReached() throws IOException
    {
        final File file = fileWith(Map.of());
        addMarkdown(CHART_REVIEW);
        this.client.failure = new IOException("connection refused");

        assertNull(this.service.classify(file, CATEGORIES), "unreachable is not a verdict");
    }

    @Test
    void fallsBackToTheDefaultBudgetWhenTheSettingsCannotBeRead() throws IOException
    {
        this.settingsUnreadable = true;
        final File file = fileWith(Map.of());
        addMarkdown(CHART_REVIEW);
        this.client.replies.add(PICKS_DATA);

        assertNotNull(this.service.classify(file, CATEGORIES));
        assertTrue(lastAsked().contains("chart review of existing records"));
    }

    @Test
    void opensWithTheGlossaryThenTheCategories() throws IOException
    {
        final File file = fileWith(Map.of());
        addMarkdown(CHART_REVIEW);
        this.client.replies.add(PICKS_DATA);

        this.service.classify(file, CATEGORIES);

        assertTrue(lastTold().startsWith("## PROTOCOL_STRUCTURE_GLOSSARY"),
            "the same opening as the intake, so the two share a cached prefix");
        assertTrue(lastTold().contains(CategoryCatalog.HEADER));
        assertTrue(lastTold().contains(DATA + ": Retrospective Data Studies -- Chart reviews."));
        assertTrue(lastTold().indexOf(CategoryCatalog.HEADER) < lastTold().indexOf("# Role"),
            "the constant blocks come before the instructions");
    }
}
