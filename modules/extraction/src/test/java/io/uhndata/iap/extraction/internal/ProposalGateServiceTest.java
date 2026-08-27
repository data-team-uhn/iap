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

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceWrapper;
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
import io.uhndata.iap.llm.LLMMessage;
import io.uhndata.iap.llm.LLMRequestOptions;
import io.uhndata.iap.submissions.models.Chunk;
import io.uhndata.iap.submissions.models.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ProposalGateService}: what the gate is shown, what it makes of the answer, and the way
 * it lets a document through whenever it cannot decide.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ProposalGateServiceTest
{
    private static final String FILE_PATH = "/Submissions/s1/proposal/v1/file";

    private static final String CHUNK_1 = "Chunk-1";

    private static final String YES = "{\"is_proposal\": true, \"confidence\": 0.9,"
        + " \"reasoning\": \"It states aims and endpoints.\","
        + " \"chunk_tags\": [{\"chunk_id\": \"Chunk-1\", \"tag\": \"B.1\", \"confidence\": 0.8}]}";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private ProposalGateService gate;

    private StubClient client;

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
        this.gate = new ProposalGateService();
        this.client = new StubClient();
        final LLMClientFactory factory = new LLMClientFactory()
        {
            @Override
            public LLMClient getClient(final String providerApi)
            {
                return ProposalGateServiceTest.this.client;
            }

            @Override
            public LLMClient getActiveClient()
            {
                return ProposalGateServiceTest.this.client;
            }
        };
        final Field field = ProposalGateService.class.getDeclaredField("llmClientFactory");
        field.setAccessible(true);
        field.set(this.gate, factory);
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

    private void addChunk(final String name, final String text)
    {
        final String chunksPath = FILE_PATH + "/chunks";
        if (this.context.resourceResolver().getResource(chunksPath) == null) {
            this.context.create().resource(chunksPath, Map.of("sling:resourceType", "sub/Chunks"));
        }
        final Resource chunk = this.context.create().resource(chunksPath + "/" + name,
            Map.of("sling:resourceType", "sub/Chunk"));
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

    private Resource fileResource()
    {
        return this.context.resourceResolver().getResource(FILE_PATH);
    }

    private Chunk chunk(final String name)
    {
        final Chunk model = this.context.resourceResolver()
            .getResource(FILE_PATH + "/chunks/" + name).adaptTo(Chunk.class);
        assertNotNull(model);
        return model;
    }

    private String lastAsked()
    {
        return this.client.asked.get(this.client.asked.size() - 1);
    }

    private String lastTold()
    {
        return this.client.told.get(this.client.told.size() - 1);
    }

    @Test
    void showsTheDocumentsOwnBookmarksWhenItHasThem() throws IOException
    {
        final File file = fileWith(Map.of("bookmarks", new String[]{ "1 Background", "2 Methods" },
            "chunked", true));
        addChunk(CHUNK_1, "# Background\n\nWhy.\n");
        this.client.replies.add(YES);

        final GateDecision decision = this.gate.evaluate(file);

        assertEquals(Verdict.PROPOSAL, decision.verdict());
        assertEquals(0.9, decision.confidence());
        assertTrue(lastAsked().contains("1 Background"), "the bookmarks are the outline it is shown");
        assertTrue(lastAsked().contains("## INPUT (table of contents)"), "the header says which form it is");
        assertFalse(lastAsked().contains("opening of the document"), "there is no need for the text as well");
    }

    @Test
    void fallsBackToTheOpeningOfTheDocument() throws IOException
    {
        final File file = fileWith(Map.of("chunked", true));
        addChunk(CHUNK_1, "# Background\n\nWhy the study is being done.\n");
        addChunk("Chunk-2", "# Methods\n\nHow.\n");
        this.client.replies.add(YES);

        this.gate.evaluate(file);

        assertTrue(lastAsked().contains("## INPUT (opening of the document)"));
        assertTrue(lastAsked().contains("Why the study is being done."));
    }

    @Test
    void stopsShowingTheOpeningOnceTheBudgetIsSpent() throws IOException
    {
        final File file = fileWith(Map.of("chunked", true));
        // Each chunk is worth the whole budget on its own, so only the first can be shown
        addChunk(CHUNK_1, "A".repeat(ProposalGateService.INPUT_TOKEN_BUDGET * 4));
        addChunk("Chunk-2", "B".repeat(1000));
        this.client.replies.add(YES);

        this.gate.evaluate(file);

        assertFalse(lastAsked().contains("BBB"), "the second chunk does not fit");
    }

    @Test
    void stopsBeforeAChunkThatWouldOverrunTheBudget() throws IOException
    {
        final File file = fileWith(Map.of("chunked", true));
        // Two thirds of the budget each: the first fits, the second would take it past
        final int twoThirds = ProposalGateService.INPUT_TOKEN_BUDGET * 4 * 2 / 3;
        addChunk(CHUNK_1, "A".repeat(twoThirds));
        addChunk("Chunk-2", "B".repeat(twoThirds));
        this.client.replies.add(YES);

        this.gate.evaluate(file);

        assertTrue(lastAsked().contains("AAA"));
        assertFalse(lastAsked().contains("BBB"), "the second chunk would not fit");
    }

    @Test
    void cannotTellWhenTheWholeDocumentSaysNothing() throws IOException
    {
        final File file = fileWith(Map.of("unchunkedReason", "below_min_structure_tokens"));
        addMarkdown("   ");

        final GateDecision decision = this.gate.evaluate(file);

        assertEquals(Verdict.UNDETERMINED, decision.verdict());
        assertTrue(this.client.asked.isEmpty(), "there was nothing worth asking about");
    }

    @Test
    void cannotTellWhenTheModelSaysNothingAtAll() throws IOException
    {
        final File file = fileWith(Map.of("bookmarks", new String[]{ "1 Background" }));
        this.client.replies.add("");
        this.client.replies.add("   ");

        assertEquals(Verdict.UNDETERMINED, this.gate.evaluate(file).verdict());
    }

    @Test
    void cannotTellWhenTheAnswerLooksLikeJsonButIsNot() throws IOException
    {
        final File file = fileWith(Map.of("bookmarks", new String[]{ "1 Background" }));
        this.client.replies.add("{ this is not, really, json }");
        this.client.replies.add("{ still: not }");

        assertEquals(Verdict.UNDETERMINED, this.gate.evaluate(file).verdict());
    }

    @Test
    void readsAWholeDocumentThatWasNeverSplitUp() throws IOException
    {
        final File file = fileWith(Map.of("unchunkedReason", "below_min_structure_tokens"));
        addMarkdown("# A small proposal\n\nAll of it.\n");
        this.client.replies.add(YES);

        this.gate.evaluate(file);

        assertTrue(lastAsked().contains("All of it."));
        assertTrue(lastAsked().contains("## INPUT (full document)"), "the header says which form it is");
    }

    @Test
    void cannotTellAboutADocumentTheIngesterMarkedOverLimit() throws IOException
    {
        // ParseResultIngester already decided this one, at ingest, and the gate only reads that - it does
        // not re-derive tokens/reason/context-limit itself.
        final File file = fileWith(Map.of("unchunkedOverLimit", true));
        addMarkdown("# A proposal too large to safely show whole\n\nAll of it.\n");

        final GateDecision decision = this.gate.evaluate(file);

        assertEquals(Verdict.UNDETERMINED, decision.verdict());
        assertTrue(this.client.asked.isEmpty(), "there was nothing safe to show, so nothing was asked");
    }

    @Test
    void ignoresBookmarksOnADocumentTheIngesterMarkedOverLimit() throws IOException
    {
        // Bookmarks make no difference to this flag: a document this large that chunking failed on
        // outright is not something a bare table of contents can be trusted to stand in for.
        final File file = fileWith(Map.of(
            "unchunkedOverLimit", true,
            "bookmarks", new String[]{ "1 Background" }));

        final GateDecision decision = this.gate.evaluate(file);

        assertEquals(Verdict.UNDETERMINED, decision.verdict());
        assertTrue(this.client.asked.isEmpty(), "not even the bookmarks are shown");
    }

    @Test
    void showsTheRubricsItJudgesAndTagsAgainst() throws IOException
    {
        final File file = fileWith(Map.of("bookmarks", new String[]{ "1 Background" }));
        this.client.replies.add(YES);

        this.gate.evaluate(file);

        assertTrue(lastTold().startsWith("## PROTOCOL_STRUCTURE"),
            "the constant part comes first, so every call in a pipeline shares the same opening");
        assertTrue(lastTold().contains("B.17"), "all of the rubrics the tags come from");
        assertTrue(lastTold().indexOf("# Role") > lastTold().indexOf("B.17"),
            "the instructions come after the reference, not before it");
        assertFalse(lastAsked().contains("B.17"),
            "and it is not repeated in the message that changes per document");
    }

    @Test
    void leavesOutTheCatalogForADocumentWithNoChunks() throws IOException
    {
        final File file = fileWith(Map.of("unchunkedReason", "below_min_structure_tokens"));
        addMarkdown("# A small proposal\n\nAll of it.\n");
        this.client.replies.add(YES);

        this.gate.evaluate(file);

        assertFalse(lastAsked().contains("## CATALOG"), "there is nothing to tag");
    }

    @Test
    void readsTheChunksRatherThanTheWholeMarkdownWhenTheDocumentWasSplitUp() throws IOException
    {
        final File file = fileWith(Map.of("chunked", true));
        addChunk(CHUNK_1, "# Background\n\nWhy the study is being done.\n");
        addMarkdown("the whole document\n");
        this.client.replies.add(YES);

        this.gate.evaluate(file);

        assertTrue(lastAsked().contains("Why the study is being done."));
        assertFalse(lastAsked().contains("the whole document"),
            "the whole Markdown is only read for a document left whole");
    }

    @Test
    void namesEveryChunkByTheHeadingItOpensWith() throws IOException
    {
        final File file = fileWith(Map.of("bookmarks", new String[]{ "1 Background" }, "chunked", true));
        addChunk(CHUNK_1, "# Background\n\nWhy.\n");
        addChunk("Chunk-2", "continued, with no heading of its own\n");
        this.client.replies.add(YES);

        this.gate.evaluate(file);

        assertTrue(lastAsked().contains("Chunk-1: Background"));
        assertTrue(lastAsked().contains("Chunk-2: "), "a chunk with no heading is still listed");
    }

    @Test
    void namesAChunkByAllItsHeadingsAtTheTwoTopmostLevels() throws IOException
    {
        final File file = fileWith(Map.of("bookmarks", new String[]{ "1 Background" }, "chunked", true));
        addChunk(CHUNK_1, "## Background\n\nWhy.\n\n### Rationale\n\nBecause.\n\n#### Prior work\n\nSee.\n");
        this.client.replies.add(YES);

        this.gate.evaluate(file);

        assertTrue(lastAsked().contains("Chunk-1: Background; Rationale"),
            "the deepest level, Prior work, is left out");
    }

    @Test
    void carriesAHeadingOverToTheChunkThatContinuesIt() throws IOException
    {
        final File file = fileWith(Map.of("bookmarks", new String[]{ "1 Background" }, "chunked", true));
        addChunk(CHUNK_1, "## Background\n\nWhy the study is being done, which runs onto");
        addChunk("Chunk-2", "the next chunk with no heading of its own.\n");
        this.client.replies.add(YES);

        this.gate.evaluate(file);

        assertTrue(lastAsked().contains("Chunk-2: Background"), "it continues the section Chunk-1 opened");
    }

    @Test
    void carriesOverOnlyTheLastHeadingWhenThePreviousChunkHeldSeveral() throws IOException
    {
        final File file = fileWith(Map.of("bookmarks", new String[]{ "1 Background" }, "chunked", true));
        addChunk(CHUNK_1,
            "## Background\n\nWhy.\n\n### Rationale\n\nBecause it runs onto");
        addChunk("Chunk-2", "the next chunk with no heading of its own.\n");
        this.client.replies.add(YES);

        this.gate.evaluate(file);

        assertTrue(lastAsked().contains("Chunk-2: Rationale"), "only the innermost heading is carried forward");
        assertFalse(lastAsked().contains("Chunk-2: Background; Rationale"),
            "not every heading Chunk-1 held");
    }

    @Test
    void addsAContinuationChunksOwnHeadingsAfterTheCarriedOverOne() throws IOException
    {
        final File file = fileWith(Map.of("bookmarks", new String[]{ "1 Background" }, "chunked", true));
        addChunk(CHUNK_1, "## Background\n\nWhy the study is being done, which runs onto");
        addChunk("Chunk-2", "the next chunk, before it reaches\n\n### Details\n\nmore of its own.\n");
        this.client.replies.add(YES);

        this.gate.evaluate(file);

        assertTrue(lastAsked().contains("Chunk-2: Background; Details"),
            "the carried-over heading comes first, then what the chunk holds itself");
    }

    @Test
    void readsTheTagsTheGateAssigned() throws IOException
    {
        final File file = fileWith(Map.of("bookmarks", new String[]{ "1 Background" }));
        this.client.replies.add(YES);

        final GateDecision decision = this.gate.evaluate(file);

        assertEquals(1, decision.chunkTags().size());
        assertEquals(CHUNK_1, decision.chunkTags().get(0).chunkId());
        assertEquals("B.1", decision.chunkTags().get(0).tag());
        assertEquals(0.8, decision.chunkTags().get(0).confidence());
    }

    @Test
    void leavesOutTagsItCannotUse() throws IOException
    {
        final File file = fileWith(Map.of("bookmarks", new String[]{ "1 Background" }));
        this.client.replies.add("{\"is_proposal\": true, \"chunk_tags\": ["
            + "\"not an object\","
            + "{\"tag\": \"B.2\"},"
            + "{\"chunk_id\": \"Chunk-9\"},"
            + "{\"chunk_id\": \"Chunk-3\", \"tag\": \"B.3\"}]}");

        final GateDecision decision = this.gate.evaluate(file);

        assertEquals(1, decision.chunkTags().size());
        assertEquals("Chunk-3", decision.chunkTags().get(0).chunkId());
        assertEquals(0.0, decision.chunkTags().get(0).confidence(), "an unstated confidence is none");
    }

    @Test
    void copesWithAnAnswerThatStatesNoConfidence() throws IOException
    {
        final File file = fileWith(Map.of("bookmarks", new String[]{ "1 Background" }));
        this.client.replies.add("{\"is_proposal\": false, \"confidence\": null, \"reasoning\": \"A consent form.\"}");

        final GateDecision decision = this.gate.evaluate(file);

        assertEquals(Verdict.NOT_PROPOSAL, decision.verdict());
        assertEquals(0.0, decision.confidence());
        assertEquals("A consent form.", decision.reasoning());
        assertTrue(decision.chunkTags().isEmpty());
    }

    @Test
    void takesTheJsonOutOfAnAnswerWrappedInProse() throws IOException
    {
        final File file = fileWith(Map.of("bookmarks", new String[]{ "1 Background" }));
        this.client.replies.add("Certainly! Here is the result:\n" + YES + "\nHope that helps.");

        assertEquals(Verdict.PROPOSAL, this.gate.evaluate(file).verdict());
    }

    @Test
    void asksAgainWhenTheAnswerIsNotTheShapeItHadToBe() throws IOException
    {
        final File file = fileWith(Map.of("bookmarks", new String[]{ "1 Background" }));
        this.client.replies.add("I cannot answer that.");
        this.client.replies.add(YES);

        final GateDecision decision = this.gate.evaluate(file);

        assertEquals(Verdict.PROPOSAL, decision.verdict());
        assertEquals(2, this.client.asked.size());
        assertTrue(lastAsked().contains("# Correction"));
    }

    @Test
    void cannotTellWhenTwoAnswersAreBothUnusable() throws IOException
    {
        final File file = fileWith(Map.of("bookmarks", new String[]{ "1 Background" }));
        this.client.replies.add("no");
        this.client.replies.add("{\"something\": \"else\"}");

        final GateDecision decision = this.gate.evaluate(file);

        assertEquals(Verdict.UNDETERMINED, decision.verdict(),
            "an unreachable model is not a yes");
    }

    @Test
    void cannotTellWhenTheModelCannotBeReached() throws IOException
    {
        final File file = fileWith(Map.of("bookmarks", new String[]{ "1 Background" }));
        this.client.failure = new IOException("the model is unreachable");

        final GateDecision decision = this.gate.evaluate(file);

        assertEquals(Verdict.UNDETERMINED, decision.verdict(),
            "an unreachable model is not a yes");
    }

    @Test
    void cannotTellAboutADocumentThereIsNothingToShowFor() throws IOException
    {
        final File file = fileWith(Map.of());

        final GateDecision decision = this.gate.evaluate(file);

        assertEquals(Verdict.UNDETERMINED, decision.verdict());
        assertTrue(this.client.asked.isEmpty(), "there was nothing worth asking about");
    }

    @Test
    void writesTheTagsOntoTheChunksAsGuesses() throws Exception
    {
        fileWith(Map.of());
        addChunk(CHUNK_1, "# Background\n");
        addChunk("Chunk-2", "# Methods\n");
        final GateDecision decision = new GateDecision(Verdict.PROPOSAL, 0.9, "",
            List.of(new ProposalGateService.ChunkTag("Chunk-2", "B.4", 0.7)));

        this.gate.applyTags(fileResource(), decision);

        final Chunk tagged = chunk("Chunk-2");
        assertEquals(List.of("B.4"), tagged.getRubricTags());
        assertEquals("heading", tagged.getTagBasis(), "the gate never read the chunk itself");
        assertTrue(tagged.isUncertain(), "a heading-only guess is not to be trusted as it stands");
        assertTrue(chunk(CHUNK_1).getRubricTags().isEmpty(), "an untagged chunk is left alone");
    }

    @Test
    void writesNothingWhenThereIsNothingToWrite() throws Exception
    {
        fileWith(Map.of());
        addChunk(CHUNK_1, "# Background\n");

        this.gate.applyTags(fileResource(), GateDecision.undetermined());

        assertTrue(chunk(CHUNK_1).getRubricTags().isEmpty());
    }

    @Test
    void writesNothingForADocumentWithNoChunkTree() throws Exception
    {
        fileWith(Map.of());
        final GateDecision decision = new GateDecision(Verdict.PROPOSAL, 0.9, "",
            List.of(new ProposalGateService.ChunkTag(CHUNK_1, "B.1", 0.7)));

        this.gate.applyTags(fileResource(), decision);
    }

    @Test
    void passesOverATagForAChunkTheDocumentDoesNotHave() throws Exception
    {
        fileWith(Map.of());
        addChunk(CHUNK_1, "# Background\n");
        final GateDecision decision = new GateDecision(Verdict.PROPOSAL, 0.9, "",
            List.of(new ProposalGateService.ChunkTag("Chunk-9", "B.9", 0.7),
                new ProposalGateService.ChunkTag(CHUNK_1, "B.1", 0.8)));

        this.gate.applyTags(fileResource(), decision);

        assertEquals(List.of("B.1"), chunk(CHUNK_1).getRubricTags(), "the tags it can place still land");
    }

    @Test
    void refusesToTagChunksItCannotWriteTo() throws Exception
    {
        fileWith(Map.of());
        addChunk(CHUNK_1, "# Background\n");
        final GateDecision decision = new GateDecision(Verdict.PROPOSAL, 0.9, "",
            List.of(new ProposalGateService.ChunkTag(CHUNK_1, "B.1", 0.8)));

        assertThrows(PersistenceException.class,
            () -> this.gate.applyTags(new ReadOnlyChunks(fileResource()), decision));
    }

    /**
     * A file whose chunks cannot be written to, standing in for one the caller has no write access to.
     */
    private static final class ReadOnlyChunks extends ResourceWrapper
    {
        ReadOnlyChunks(final Resource wrapped)
        {
            super(wrapped);
        }

        @Override
        public Resource getChild(final String relPath)
        {
            final Resource child = super.getChild(relPath);
            return child == null ? null : new ReadOnlyChild(child);
        }
    }

    /**
     * Hands out children that refuse to be adapted for writing.
     */
    private static final class ReadOnlyChild extends ResourceWrapper
    {
        ReadOnlyChild(final Resource wrapped)
        {
            super(wrapped);
        }

        @Override
        public Resource getChild(final String relPath)
        {
            final Resource child = super.getChild(relPath);
            return child == null ? null : new ReadOnlyChild(child);
        }

        @Override
        public <T> T adaptTo(final Class<T> type)
        {
            if (ModifiableValueMap.class.equals(type)) {
                return null;
            }
            return super.adaptTo(type);
        }
    }

    @Test
    void refusesAPromptTheBundleDoesNotCarry()
    {
        assertThrows(java.io.UncheckedIOException.class, () -> Prompts.read("no_such_prompt.md"));
    }

    @Test
    void readsThePromptsItDoesCarry()
    {
        assertTrue(Prompts.read(Prompts.IS_PROPOSAL_SYSTEM).contains("research proposal"));
        assertTrue(Prompts.read(Prompts.IS_PROPOSAL_SCHEMA).contains("is_proposal"));
        assertTrue(Prompts.read(Prompts.IS_PROPOSAL_SYSTEM).contains("research proposal"),
            "and again, from the cache");
    }
}
