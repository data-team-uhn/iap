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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.llm.LLMClient;
import io.uhndata.iap.llm.LLMClientFactory;
import io.uhndata.iap.llm.LLMMessage;
import io.uhndata.iap.llm.LLMRequestOptions;
import io.uhndata.iap.submissions.models.Chunk;
import io.uhndata.iap.submissions.models.Chunks;
import io.uhndata.iap.submissions.models.File;

/**
 * Decides whether an uploaded document is a research proposal at all, tags its chunks while it is looking, and
 * when it is one, picks which category of study it describes - the category decides which schema the answers
 * are read into, so it has to be known before anything else is asked.
 *
 * <p>When it cannot tell - the model is unreachable, or its answer unreadable - the verdict is
 * {@link Verdict#UNDETERMINED}. It does not guess. Extraction is skipped and the submitter fills the schema
 * in themselves. The category is held to the same rule: an answer naming no category that exists is no pick.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ProposalGateService.class)
public class ProposalGateService
{
    /** How much of the opening of a chunked document to show. */
    static final int INPUT_TOKEN_BUDGET = 10_000;

    private static final Logger LOGGER = LoggerFactory.getLogger(ProposalGateService.class);

    /** The name the provider associates with the gate's response schema. */
    private static final String SCHEMA_NAME = "iap_is_proposal_gate";

    /** Room for the verdict and the category, before anything the chunk tags need. */
    private static final long BASE_TOKENS = 500L;

    /** Room for one chunk's tag in the answer. */
    private static final long TOKENS_PER_CHUNK = 30L;

    /** The reference block, which the prompt calls PROTOCOL_STRUCTURE. */
    private static final String STRUCTURE_HEADER = "## PROTOCOL_STRUCTURE";

    /**
     * The document block, which the prompt calls INPUT. It comes in up to two parts, each under its own header,
     * because the prompt tells the model to weigh an outline differently from the text itself.
     */
    private static final String INPUT_TABLE_OF_CONTENTS = "## INPUT (table of contents) (untrusted data)";

    private static final String INPUT_FULL_DOCUMENT = "## INPUT (full document) (untrusted data)";

    private static final String INPUT_OPENING = "## INPUT (opening of the document) (untrusted data)";

    /** The chunk-tagging targets, which the prompt calls CATALOG. */
    private static final String CATALOG_HEADER = "## CATALOG (chunk id: heading) (untrusted data)";

    /** What separates one section of the message from the next. */
    private static final String BREAK = "\n\n";

    private static final String CORRECTION =
        "\n\n# Correction\n\nYour previous answer was not a single JSON object matching the schema. "
            + "Answer again with exactly one such object and nothing else.";

    @Reference
    private LLMClientFactory llmClientFactory;

    /**
     * One chunk's rubric tag, as the gate assigned it.
     *
     * @param chunkId the chunk the tag is for
     * @param tag the rubric tag
     * @param confidence how sure the model was, from 0 to 1
     * @version $Id$
     * @since 0.1.0
     */
    public record ChunkTag(String chunkId, String tag, double confidence)
    {
    }

    /**
     * What the gate made of a document.
     *
     * @version $Id$
     * @since 0.1.0
     */
    public enum Verdict
    {
        /** A research proposal. Extraction runs. */
        PROPOSAL,

        /** Something else, such as a consent form. Extraction is skipped. */
        NOT_PROPOSAL,

        /**
         * The gate could not tell: the model was unreachable, or its answer unreadable. Extraction is
         * skipped and the submitter fills the schema in themselves.
         */
        UNDETERMINED
    }

    /**
     * What the gate decided.
     *
     * @param verdict what the document is, as far as the gate could tell
     * @param confidence how sure the model was, from 0 to 1
     * @param reasoning why, in the model's words
     * @param chunkTags the rubric tag assigned to each chunk
     * @param category the category the model filed a proposal under, or {@code null} when it named none that
     *            exists - which is not a verdict against any category, just no pick
     * @version $Id$
     * @since 0.1.0
     */
    public record GateDecision(Verdict verdict, double confidence, String reasoning, List<ChunkTag> chunkTags,
        CategoryPick category)
    {
        /**
         * Takes a copy of the tags, so a decision cannot be changed after it was made.
         *
         * @param verdict what the document is, as far as the gate could tell
         * @param confidence how sure the model was, from 0 to 1
         * @param reasoning why, in the model's words
         * @param chunkTags the rubric tag assigned to each chunk
         * @param category the category picked, or {@code null} for none
         */
        public GateDecision
        {
            chunkTags = List.copyOf(chunkTags);
        }

        /**
         * A decision with no category picked.
         *
         * @param verdict what the document is, as far as the gate could tell
         * @param confidence how sure the model was, from 0 to 1
         * @param reasoning why, in the model's words
         * @param chunkTags the rubric tag assigned to each chunk
         */
        public GateDecision(final Verdict verdict, final double confidence, final String reasoning,
            final List<ChunkTag> chunkTags)
        {
            this(verdict, confidence, reasoning, chunkTags, null);
        }

        /**
         * The decision when the gate could not reach a verdict. It does not guess either way: guessing
         * proposal pre-fills answers from a document nobody checked, and guessing not-a-proposal throws away
         * a good one.
         *
         * @return a decision saying the document could not be identified
         */
        static GateDecision undetermined()
        {
            return new GateDecision(Verdict.UNDETERMINED, 0.0, "", List.of());
        }
    }

    /**
     * Decide whether a parsed document is a research proposal, and tag its chunks, with no categories to file
     * it under.
     *
     * @param file the parsed file to weigh up
     * @return what the gate decided, undetermined when it could not tell
     * @throws IOException if the document cannot be read
     */
    public GateDecision evaluate(final File file) throws IOException
    {
        return evaluate(file, List.of());
    }

    /**
     * Decide whether a parsed document is a research proposal, tag its chunks, and when it is one, pick which
     * of the given categories it belongs under.
     *
     * @param file the parsed file to weigh up
     * @param categories the categories a proposal may be filed under, see {@link CategoryCatalog#read}
     * @return what the gate decided, undetermined when it could not tell
     * @throws IOException if the document cannot be read
     */
    public GateDecision evaluate(final File file, final List<CategoryCatalog.Entry> categories)
        throws IOException
    {
        final List<Chunk> chunks = listChunks(file);
        final String input = describeInput(file, chunks);
        if (input.isBlank()) {
            LOGGER.warn("Nothing could be shown to the gate for {}; cannot tell what the document is",
                file.getPath());
            return GateDecision.undetermined();
        }
        try {
            return ask(buildUserMessage(input, chunks), chunks.size(), categories);
        } catch (IOException e) {
            LOGGER.warn("The gate could not be asked about {}: {}", file.getPath(), e.getMessage());
            return GateDecision.undetermined();
        }
    }

    /**
     * The blocks that change from one document to the next: INPUT, the document; and CATALOG, the chunks waiting
     * for a tag. CATALOG is left out for a document that was never chunked, since it has none.
     *
     * <p>PROTOCOL_STRUCTURE is not here. It is the same on every call, so it belongs in the system prompt where
     * it can be cached - see {@link #buildSystemPrompt}.
     *
     * @param input the INPUT block, already headed by which form it is
     * @param chunks the document's chunks
     * @return the part of the message that is about this document
     * @throws IOException if a chunk cannot be read
     */
    private static String buildUserMessage(final String input, final List<Chunk> chunks) throws IOException
    {
        final StringBuilder message = new StringBuilder(input);
        if (!chunks.isEmpty()) {
            message.append(BREAK).append(describeCatalog(chunks));
        }
        return message.toString();
    }

    /**
     * The reference first (protocol full structure), then the categories, then what to do with them
     * (instructions).
     *
     * <p>That order is deliberate. PROTOCOL_STRUCTURE is identical on every call, and the categories change
     * only when an administrator edits them, so every call shares a byte-identical opening, which is what a
     * provider's prefix cache matches on and what a local model reuses its KV cache for.
     *
     * @param categories the categories a proposal may be filed under; the block is left out when there are none
     * @return the system prompt
     */
    private static String buildSystemPrompt(final List<CategoryCatalog.Entry> categories)
    {
        final StringBuilder system = new StringBuilder(STRUCTURE_HEADER).append(BREAK)
            .append(Prompts.read(Prompts.PROTOCOL_STRUCTURE).strip()).append(BREAK);
        if (!categories.isEmpty()) {
            system.append(CategoryCatalog.describe(categories)).append(BREAK);
        }
        return system.append(Prompts.read(Prompts.IS_PROPOSAL_SYSTEM)).toString();
    }

    /**
     * Write the tags the gate assigned onto the chunks themselves.
     *
     * <p>A separate step from {@link #evaluate}, so that a document turned away at the gate leaves nothing
     * behind. The caller decides when the decision is worth keeping.
     *
     * <p>The gate assigns these from headings alone, without reading the chunks, so the confidence it gave
     * is recorded beside them for a later stage to firm up or replace.
     *
     * @param file the {@code sub:File} node the gate looked at
     * @param decision what the gate decided
     * @throws PersistenceException if the tags cannot be written
     */
    public void applyTags(final Resource file, final GateDecision decision) throws PersistenceException
    {
        final Resource chunksResource = file.getChild(ParsePropertyNames.CHUNKS_CHILD);
        if (chunksResource == null || decision.chunkTags().isEmpty()) {
            return;
        }
        final Set<String> tagged = new HashSet<>();
        for (final ChunkTag tag : decision.chunkTags()) {
            if (!tagged.add(tag.chunkId())) {
                // The model named this chunk twice. The first answer stands, so a later contradiction
                // cannot quietly overwrite what it led with.
                continue;
            }
            final Resource chunk = chunksResource.getChild(tag.chunkId());
            if (chunk == null) {
                LOGGER.warn("The gate tagged a chunk this document does not have");
                continue;
            }
            final ModifiableValueMap properties = chunk.adaptTo(ModifiableValueMap.class);
            if (properties == null) {
                throw new PersistenceException("Not allowed to tag " + chunk.getPath());
            }
            properties.put(ParsePropertyNames.RUBRIC_TAGS, new String[]{ tag.tag() });
            properties.put(ParsePropertyNames.TAG_CONFIDENCE, tag.confidence());
        }
    }

    /**
     * The chunks of a document, in the order a reader would meet them.
     *
     * @param file the parsed file
     * @return its chunks, empty when it was left whole
     */
    private static List<Chunk> listChunks(final File file)
    {
        final Chunks holder = file.getChunks();
        return holder == null ? List.of() : holder.getChunks();
    }

    /**
     * The INPUT block, in up to two parts: the document's own bookmarks when it has them, then its text - a
     * document the parser left whole, shown whole, or otherwise as much of a chunked document's opening as
     * {@link #INPUT_TOKEN_BUDGET} allows. Bookmarks alone say what shape a document has, not what kind of study
     * it describes, so the text goes along whenever there is any.
     *
     * <p>{@link File#isUnchunkedOverLimit()} is checked first, ahead of even bookmarks: it means the document
     * went unchunked for a reason that says nothing about its size, and is still past the whole-document token
     * limit, decided once at ingest.
     *
     * @param file the parsed file
     * @param chunks its chunks
     * @return the INPUT section of the message, blank when the document offers nothing safe to show
     * @throws IOException if a chunk cannot be read
     */
    private static String describeInput(final File file, final List<Chunk> chunks) throws IOException
    {
        if (file.isUnchunkedOverLimit()) {
            return "";
        }
        final StringBuilder input = new StringBuilder();
        final List<String> bookmarks = file.getBookmarks();
        if (bookmarks != null && !bookmarks.isEmpty()) {
            input.append(INPUT_TABLE_OF_CONTENTS).append(BREAK).append(String.join("\n", bookmarks));
        }
        final String text = describeText(file, chunks);
        if (!text.isEmpty()) {
            if (!input.isEmpty()) {
                input.append(BREAK);
            }
            input.append(text);
        }
        return input.toString();
    }

    /**
     * The text part of INPUT: the whole of a document the parser left whole, or the opening of a chunked one.
     *
     * @param file the parsed file
     * @param chunks its chunks
     * @return the text under its header, blank when there is none
     * @throws IOException if the text cannot be read
     */
    private static String describeText(final File file, final List<Chunk> chunks) throws IOException
    {
        if (!file.isChunked()) {
            final String whole = truncate(readWholeDocument(file));
            return whole.isBlank() ? "" : INPUT_FULL_DOCUMENT + BREAK + whole;
        }
        final String opening = readOpening(chunks);
        return opening.isBlank() ? "" : INPUT_OPENING + BREAK + opening;
    }

    /**
     * The opening of a chunked document, up to the token budget.
     *
     * @param chunks its chunks
     * @return the text to show, truncated to the budget
     * @throws IOException if the text cannot be read
     */
    private static String readOpening(final List<Chunk> chunks) throws IOException
    {
        final StringBuilder opening = new StringBuilder();
        int used = 0;
        for (final Chunk chunk : chunks) {
            final String text = ChunkContent.readText(chunk);
            if (used > 0 && used + ChunkContent.estimateTokens(text) > INPUT_TOKEN_BUDGET) {
                break;
            }
            opening.append(text).append(BREAK);
            used += ChunkContent.estimateTokens(text);
            if (used >= INPUT_TOKEN_BUDGET) {
                break;
            }
        }
        return truncate(opening.toString());
    }

    /**
     * The Markdown rendition of a document that was never split up.
     *
     * @param file the parsed file
     * @return its text, or an empty string when it carries none
     * @throws IOException if the text cannot be read
     */
    private static String readWholeDocument(final File file) throws IOException
    {
        return ChunkContent.readText(file.getFileMarkdown());
    }

    private static String truncate(final String text)
    {
        final int limit = INPUT_TOKEN_BUDGET * ChunkContent.CHARS_PER_TOKEN;
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    /**
     * Creates a catalog as list of chunk names and their top 3 level headings separated by ";".
     *
     * <p>A chunk that opens mid-prose is a continuation of whatever section the chunk before it was in, so it
     * is named by that chunk's last heading too - the innermost one it reached, carried forward in front of
     * any headings this chunk goes on to hold itself, rather than left blank just because it does not open
     * with one of its own.
     *
     * @param chunks the document's chunks
     * @return the catalog section of the message
     * @throws IOException if a chunk cannot be read
     */
    private static String describeCatalog(final List<Chunk> chunks) throws IOException
    {
        final StringBuilder catalog = new StringBuilder(CATALOG_HEADER).append(BREAK);
        for (final ChunkCatalog.Entry entry : ChunkCatalog.read(chunks)) {
            catalog.append(entry.getName()).append(": ").append(entry.describeHeadings()).append('\n');
        }
        return catalog.toString();
    }

    /**
     * Put the question to the model, once, and once more if the first answer was not the shape it had to be.
     *
     * @param userMessage what to show the model
     * @param chunkCount how many chunks are waiting to be tagged
     * @param categories the categories a proposal may be filed under
     * @return the decision, undetermined when neither answer could be read
     * @throws IOException if the model cannot be reached
     */
    private GateDecision ask(final String userMessage, final int chunkCount,
        final List<CategoryCatalog.Entry> categories) throws IOException
    {
        final LLMClient client = this.llmClientFactory.getActiveClient();
        final String system = buildSystemPrompt(categories);
        final LLMRequestOptions options = LLMRequestOptions.builder()
            .maxOutputTokens(BASE_TOKENS + TOKENS_PER_CHUNK * chunkCount)
            .jsonSchema(SCHEMA_NAME, ResponseSchemas.gate(categories))
            .build();

        final GateDecision first = readDecision(client.chat(system,
            List.of(new LLMMessage("user", userMessage)), options), categories);
        if (first != null) {
            return first;
        }
        LOGGER.warn("The gate did not answer in the required shape; asking once more");
        final GateDecision second = readDecision(client.chat(system,
            List.of(new LLMMessage("user", userMessage + CORRECTION)), options), categories);
        if (second != null) {
            return second;
        }
        LOGGER.warn("The gate did not answer in the required shape twice; cannot tell what the document is");
        return GateDecision.undetermined();
    }

    /**
     * Read the model's answer.
     *
     * @param reply what it said
     * @param categories the categories it could have picked from
     * @return the decision, or {@code null} when the answer was not the shape it had to be, which leaves the
     *         verdict undetermined rather than settling it either way
     */
    private static GateDecision readDecision(final String reply, final List<CategoryCatalog.Entry> categories)
    {
        if (reply == null || reply.isBlank()) {
            return null;
        }
        final JsonObject answer = ModelReplies.readJsonObject(reply);
        if (answer == null || !answer.containsKey("is_proposal")) {
            return null;
        }
        final Verdict verdict = answer.getBoolean("is_proposal", false) ? Verdict.PROPOSAL : Verdict.NOT_PROPOSAL;
        return new GateDecision(verdict,
            ModelReplies.readConfidence(answer, "confidence"),
            answer.getString("reasoning", ""),
            readChunkTags(answer.getJsonArray("chunk_tags")),
            verdict == Verdict.PROPOSAL ? readCategory(answer, categories) : null);
    }

    /**
     * The category the model filed the proposal under, when it named one that exists. Anything else - no
     * category, or one that is not in the tree - is no pick, and the choice is left open for a person.
     *
     * @param answer the model's answer
     * @param categories the categories it could have picked from
     * @return the pick, or {@code null} for none
     */
    private static CategoryPick readCategory(final JsonObject answer, final List<CategoryCatalog.Entry> categories)
    {
        final CategoryCatalog.Entry entry =
            CategoryCatalog.find(categories, ModelReplies.readString(answer, "category"));
        return entry == null ? null
            : new CategoryPick(entry.path(), ModelReplies.readConfidence(answer, "category_confidence"));
    }

    private static List<ChunkTag> readChunkTags(final JsonArray tags)
    {
        if (tags == null) {
            return List.of();
        }
        final List<ChunkTag> read = new ArrayList<>(tags.size());
        for (final JsonValue value : tags) {
            if (value.getValueType() != JsonValue.ValueType.OBJECT) {
                continue;
            }
            final JsonObject tag = value.asJsonObject();
            final String chunkId = tag.getString("chunk_id", null);
            // Checked against the vocabulary, not merely for being a string: an off-list tag written onto a
            // chunk would read later as a placement nothing can act on, and the intake filters the same way
            final String rubricTag = tag.getString("tag", null);
            if (chunkId != null && RubricTags.isValid(rubricTag)) {
                read.add(new ChunkTag(chunkId, rubricTag.strip(),
                    ModelReplies.readConfidence(tag, "confidence")));
            }
        }
        return read;
    }

}
