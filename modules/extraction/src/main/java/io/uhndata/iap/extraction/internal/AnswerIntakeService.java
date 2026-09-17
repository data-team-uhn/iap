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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import io.uhndata.iap.llm.LLMConfigurationService;
import io.uhndata.iap.llm.LLMMessage;
import io.uhndata.iap.llm.LLMRequestOptions;
import io.uhndata.iap.llm.LLMSettings;
import io.uhndata.iap.submissions.models.Chunk;
import io.uhndata.iap.submissions.models.Chunks;
import io.uhndata.iap.submissions.models.File;

/**
 * The first extraction pass: one call that asks for every field at once, over as much of the document as fits.
 *
 * <p>The chunks it read get their tags corrected in the same answer: the model has just read them, so what
 * it says replaces what the gate guessed from headings, and asking again would pay for the same reading twice.
 *
 * <p>Nothing is made up. Every quote is checked against the chunk it names, and a field's confidence is
 * scaled by how much of its evidence held up; a field with no value at all is reported as not found whatever
 * the model said.
 * An answer that cannot be read twice over leaves every field unanswered, marked degraded, rather than
 * settling any of them by guesswork.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = AnswerIntakeService.class)
public class AnswerIntakeService
{
    /**
     * What we multiply confidence by when none of a field's quotes could be found. 0.9 becomes 0.45. Not 0,
     * because the answer can still be right. The field goes to Step 2 either way.
     */
    static final double UNVERIFIED_PENALTY = 0.5;

    /** Below this a found answer goes to Step 2. Same floor the form uses to flag an answer. */
    static final double PENDING_CONFIDENCE = 0.75;

    private static final Logger LOGGER = LoggerFactory.getLogger(AnswerIntakeService.class);

    private static final String SCHEMA_NAME = "iap_intake";

    /** Room for the envelope of the answer. */
    private static final long BASE_TOKENS = 500L;

    /** Room for one field's value, reasoning and quotes. */
    private static final long PER_FIELD_TOKENS = 400L;

    /** Room for one chunk's tag. */
    private static final long PER_CHUNK_TOKENS = 30L;

    private static final String GLOSSARY_HEADER = "## PROTOCOL_STRUCTURE_GLOSSARY";

    private static final String BREAK = "\n\n";

    private static final String CORRECTION_OPENING =
        "\n\n# Correction\n\nYour previous answer could not be read: ";

    private static final String CORRECTION_CLOSING =
        ". Answer again with exactly one JSON object matching the schema and nothing else.";

    @Reference
    private LLMClientFactory llmClientFactory;

    @Reference
    private LLMConfigurationService configurationService;

    /**
     * The tags the model gave one chunk after reading it.
     *
     * @param chunkId the chunk
     * @param tags the rubric tags, as the model gave them
     * @param confidence how sure the model was, from 0 to 1
     * @version $Id$
     * @since 0.1.0
     */
    public record ChunkTagging(String chunkId, List<String> tags, double confidence)
    {
        /**
         * Takes a copy of the tags.
         *
         * @param chunkId the chunk
         * @param tags the rubric tags
         * @param confidence how sure the model was
         */
        public ChunkTagging
        {
            tags = List.copyOf(tags);
        }
    }

    /**
     * What the intake pass produced.
     *
     * @param fields one result per field asked for, by field name, in the order asked
     * @param chunkTags the tags for the chunks the model read
     * @param sentChunkIds the chunks that were sent in full, in document order
     * @param degraded whether the model's answer could not be read, leaving every field unanswered
     * @version $Id$
     * @since 0.1.0
     */
    public record IntakeResult(Map<String, FieldResult> fields, List<ChunkTagging> chunkTags,
        List<String> sentChunkIds, boolean degraded)
    {
        /**
         * Takes copies, so a result cannot be changed once produced.
         *
         * @param fields one result per field
         * @param chunkTags the tags for the chunks read
         * @param sentChunkIds the chunks sent in full
         * @param degraded whether the answer could not be read
         */
        public IntakeResult
        {
            fields = Map.copyOf(fields);
            chunkTags = List.copyOf(chunkTags);
            sentChunkIds = List.copyOf(sentChunkIds);
        }

        /**
         * The result when the model's answer could not be read twice over: nothing answered, nothing tagged.
         *
         * <p>No field results. Step 2 works out what still needs asking by reading the submission: a question
         * with no answer needs one. So a failed intake leaves every field pending without this having to say
         * so.
         *
         * @param sentChunkIds what was sent, so a later pass knows what has been looked at
         * @return a degraded result
         */
        static IntakeResult degraded(final List<String> sentChunkIds)
        {
            return new IntakeResult(Map.of(), List.of(), sentChunkIds, true);
        }
    }

    /**
     * Ask for every field over the document.
     *
     * @param file the parsed file
     * @param fields what to extract
     * @return what the model read out, degraded when its answer could not be read
     * @throws IOException if the document cannot be read or the model cannot be reached
     */
    public IntakeResult run(final File file, final List<ExtractionField> fields) throws IOException
    {
        if (fields.isEmpty()) {
            return new IntakeResult(Map.of(), List.of(), List.of(), false);
        }
        final IntakePayload payload = buildPayload(file, fields);
        if (payload == null) {
            LOGGER.warn("There is nothing to read for {}", file.getPath());
            return IntakeResult.degraded(List.of());
        }
        final JsonObject answer = ask(payload, fields);
        if (answer == null) {
            LOGGER.warn("The intake did not answer in the required shape twice for {}", file.getPath());
            return IntakeResult.degraded(payload.getSentChunkIds());
        }
        return new IntakeResult(readFields(answer, fields, payload.getSentTexts()),
            readChunkTags(answer, payload.getSentChunkIds()), payload.getSentChunkIds(), false);
    }

    /**
     * Ask for some fields over a chunk set the caller chose. Step 2 uses this: its planner decides which
     * chunks each batch has not seen, and this makes the call and reads the answer the same way the first
     * pass does.
     *
     * @param file the parsed file
     * @param fields what to extract
     * @param chunkIds the chunks to send in full
     * @return what the model read out, degraded when its answer could not be read
     * @throws IOException if the document cannot be read or the model cannot be reached
     */
    public IntakeResult runOver(final File file, final List<ExtractionField> fields, final Set<String> chunkIds)
        throws IOException
    {
        if (fields.isEmpty() || chunkIds.isEmpty()) {
            return new IntakeResult(Map.of(), List.of(), List.of(), false);
        }
        final Chunks holder = file.getChunks();
        final List<Chunk> chunks = holder == null ? List.of() : holder.getChunks();
        if (chunks.isEmpty()) {
            return new IntakeResult(Map.of(), List.of(), List.of(), false);
        }
        final IntakePayload payload =
            IntakePayload.buildForChunks(fields, ChunkCatalog.read(chunks), chunkIds);
        final JsonObject answer = ask(payload, fields);
        if (answer == null) {
            LOGGER.warn("A second-pass call did not answer in the required shape twice for {}", file.getPath());
            return IntakeResult.degraded(payload.getSentChunkIds());
        }
        return new IntakeResult(readFields(answer, fields, payload.getSentTexts()),
            readChunkTags(answer, payload.getSentChunkIds()), payload.getSentChunkIds(), false);
    }

    /**
     * Write the tags the model gave the chunks it read. A chunk that came back with no usable tag keeps what it
     * already had, rather than being guessed at or cleared.
     *
     * @param file the {@code sub:File} node the chunks belong to
     * @param result what the intake produced
     * @throws PersistenceException if the tags cannot be written
     */
    public void applyTags(final Resource file, final IntakeResult result) throws PersistenceException
    {
        final Resource chunksResource = file.getChild(ParsePropertyNames.CHUNKS_CHILD);
        if (chunksResource == null || result.chunkTags().isEmpty()) {
            return;
        }
        for (final ChunkTagging tagging : result.chunkTags()) {
            final Resource chunk = chunksResource.getChild(tagging.chunkId());
            if (chunk == null) {
                LOGGER.warn("The intake tagged a chunk this document does not have");
                continue;
            }
            final ModifiableValueMap properties = chunk.adaptTo(ModifiableValueMap.class);
            if (properties == null) {
                throw new PersistenceException("Not allowed to tag " + chunk.getPath());
            }
            final List<String> valid = RubricTags.filter(tagging.tags());
            if (valid.isEmpty()) {
                // Nothing usable came back for this chunk. Its tags are left exactly as they were rather
                // than cleared: the gate's placement is better than none, and a cleared chunk would become
                // a wildcard, which is the right default only for a chunk nothing has ever placed.
                continue;
            }
            // The model has just read this chunk, so what it says replaces what the gate guessed
            properties.put(ParsePropertyNames.RUBRIC_TAGS, valid.toArray(new String[0]));
            properties.put(ParsePropertyNames.TAG_CONFIDENCE, tagging.confidence());
        }
    }

    /**
     * The message for this document: its chunks when it has them, its whole Markdown otherwise.
     *
     * @return the payload, or {@code null} when there is no text to read
     */
    private IntakePayload buildPayload(final File file, final List<ExtractionField> fields) throws IOException
    {
        final long budget = readTokenBudget();
        final Chunks holder = file.getChunks();
        final List<Chunk> chunks = holder == null ? List.of() : holder.getChunks();
        if (!chunks.isEmpty()) {
            return IntakePayload.build(fields, ChunkCatalog.read(chunks), budget);
        }
        final String markdown = ChunkContent.readText(file.getFileMarkdown());
        return markdown.isBlank() ? null : IntakePayload.buildForWholeDocument(fields, markdown, budget);
    }

    /**
     * How much text one call may carry: the same threshold that decides whether a document is small enough to
     * leave whole. A settings problem falls back to the default rather than to no limit.
     */
    private long readTokenBudget()
    {
        try {
            return this.configurationService.getActiveSettings().getWholeDocumentTokenLimit();
        } catch (IOException e) {
            LOGGER.warn("Could not read the active LLM settings for the intake budget: {}", e.getMessage());
            return LLMSettings.DEFAULT_WHOLE_DOCUMENT_TOKEN_LIMIT;
        }
    }

    /**
     * Put the question to the model, once, and once more if the first answer was not the shape it had to be.
     *
     * @return the answer, or {@code null} when neither could be read
     * @throws IOException if the model cannot be reached
     */
    private JsonObject ask(final IntakePayload payload, final List<ExtractionField> fields) throws IOException
    {
        final LLMClient client = this.llmClientFactory.getActiveClient();
        final String system = GLOSSARY_HEADER + BREAK + Prompts.read(Prompts.PROTOCOL_STRUCTURE_GLOSSARY).strip()
            + BREAK + Prompts.read(Prompts.INTAKE_SYSTEM);
        final LLMRequestOptions options = LLMRequestOptions.builder()
            .maxOutputTokens(BASE_TOKENS + PER_FIELD_TOKENS * fields.size()
                + PER_CHUNK_TOKENS * payload.getSentChunkIds().size())
            .jsonSchema(SCHEMA_NAME, IntakePayload.buildResponseSchema(fields))
            .build();
        final String reply = client.chat(system,
            List.of(new LLMMessage("user", payload.getUserMessage())), options);
        final JsonObject first = ModelReplies.readJsonObject(reply);
        if (first != null) {
            return first;
        }
        // The re-ask says what was actually wrong rather than that something was. A model told its answer
        // was unreadable has nothing to correct; one told it held two objects, or stopped mid-string, does.
        final String fault = describeFault(reply, fields);
        LOGGER.warn("The intake did not answer in the required shape ({}); asking once more", fault);
        final String corrected = payload.getUserMessage() + CORRECTION_OPENING + fault + CORRECTION_CLOSING;
        return ModelReplies.readJsonObject(
            client.chat(system, List.of(new LLMMessage("user", corrected)), options));
    }

    /** What was wrong with a reply, in terms a model can act on. */
    private static String describeFault(final String reply, final List<ExtractionField> fields)
    {
        if (reply == null || reply.isBlank()) {
            return "it was empty";
        }
        if (!reply.contains("{")) {
            return "it contained no JSON object";
        }
        if (reply.chars().filter(c -> c == '{').count() > reply.chars().filter(c -> c == '}').count()) {
            return "the JSON object was never closed, so the answer was cut off before it finished";
        }
        return "it was not a single JSON object with a key for each of " + fields.size() + " fields";
    }

    private static Map<String, FieldResult> readFields(final JsonObject answer, final List<ExtractionField> fields,
        final Map<String, String> texts)
    {
        final Map<String, FieldResult> results = new LinkedHashMap<>();
        for (final ExtractionField field : fields) {
            final JsonObject read = ModelReplies.readObject(answer, field.name());
            results.put(field.name(), read == null
                ? new FieldResult(field.name(), false, 0.0, null, "", List.of())
                : readField(field.name(), read, texts));
        }
        return results;
    }

    /**
     * One field, held to the evidence rules: no value means not found, and a found value none of whose quotes
     * can be found in the text is discounted.
     */
    private static FieldResult readField(final String name, final JsonObject read, final Map<String, String> texts)
    {
        final String value = ModelReplies.readString(read, "value");
        final boolean found = read.getBoolean("found_answer", false) && value != null;
        final List<FieldResult.Passage> offered = readPassages(read);
        final List<FieldResult.Passage> kept = verify(offered, texts);
        // Scaled by how many quotes checked out. 2 of 2 keeps it, 1 of 2 takes off a quarter, 0 of 2 halves it.
        final double claimed = ModelReplies.readConfidence(read, "confidence");
        final double confidence = found ? claimed * evidenceFactor(kept.size(), offered.size()) : claimed;
        final String reasoning = ModelReplies.readString(read, "reasoning");
        // Goes to Step 2 if nothing was found, the answer is not sure enough, or a quote was dropped
        final boolean again = !found || confidence < PENDING_CONFIDENCE || kept.size() < offered.size();
        return new FieldResult(name, found, confidence, found ? value : null, reasoning == null ? "" : reasoning,
            kept, again);
    }

    /**
     * The quotes that are really in the text, placed in the chunk they were found in and under the heading
     * they sit below.
     *
     * <p>A quote nobody can find is dropped rather than stored. It is not evidence, and showing it to a
     * reviewer as though it were would be worse than showing none: they would read a sentence that is not in
     * their document. The answer still pays for it, through the confidence.
     */
    private static List<FieldResult.Passage> verify(final List<FieldResult.Passage> offered,
        final Map<String, String> texts)
    {
        final List<FieldResult.Passage> kept = new ArrayList<>(offered.size());
        for (final FieldResult.Passage passage : offered) {
            final Located located = locate(passage, texts);
            if (located.score() >= QuoteVerifier.MATCH_FLOOR) {
                kept.add(passage.foundIn(located.chunkId(),
                    ChunkContent.findHeadingAbove(located.text(), passage.quote())));
            }
        }
        return kept;
    }

    /**
     * Which chunk a quote turned out to be in, and how well it matched there.
     *
     * @param chunkId the chunk
     * @param text its text
     * @param score how much of the quote is in it, from 0 to 1
     * @version $Id$
     * @since 0.1.0
     */
    private record Located(String chunkId, String text, double score)
    {
    }

    /**
     * Which chunk a quote actually came from. The model's own {@code chunk_id} is tried first, and when the
     * quote is not there the sent chunks are searched for it.
     *
     * <p>This is why a quote ends up with the right chunk even when the model named the wrong one or named
     * none at all. Without it such a quote would fail the check and cost the answer confidence, when the
     * quote itself was fine and only its label was wrong.
     */
    private static Located locate(final FieldResult.Passage passage, final Map<String, String> texts)
    {
        final String named = passage.chunkId();
        final String namedText = named == null ? null : texts.get(named);
        final double namedScore = QuoteVerifier.score(passage.quote(), namedText);
        if (namedScore >= QuoteVerifier.MATCH_FLOOR) {
            return new Located(named, namedText, namedScore);
        }
        Located best = new Located(named, namedText, namedScore);
        for (final Map.Entry<String, String> chunk : texts.entrySet()) {
            final double score = QuoteVerifier.score(passage.quote(), chunk.getValue());
            if (score > best.score()) {
                best = new Located(chunk.getKey(), chunk.getValue(), score);
            }
        }
        return best;
    }

    /**
     * The multiplier for a found answer, from {@link #UNVERIFIED_PENALTY} when no quote checked out to 1 when
     * all of them did. An answer with no quotes at all gets the same 0.5 as one whose quotes all failed.
     *
     * @param kept how many quotes were really in the text
     * @param offered how many the model gave
     */
    private static double evidenceFactor(final int kept, final int offered)
    {
        if (offered == 0) {
            return UNVERIFIED_PENALTY;
        }
        return UNVERIFIED_PENALTY + (1.0 - UNVERIFIED_PENALTY) * ((double) kept / offered);
    }

    private static List<FieldResult.Passage> readPassages(final JsonObject read)
    {
        final List<FieldResult.Passage> passages = new ArrayList<>();
        if (!read.containsKey("evidence") || read.get("evidence").getValueType() != JsonValue.ValueType.ARRAY) {
            return passages;
        }
        for (final JsonValue value : read.getJsonArray("evidence")) {
            if (value.getValueType() != JsonValue.ValueType.OBJECT) {
                continue;
            }
            final JsonObject item = value.asJsonObject();
            final String quote = ModelReplies.readString(item, "quote");
            if (quote != null && !quote.isBlank()) {
                passages.add(new FieldResult.Passage(quote, ModelReplies.readString(item, "chunk_id"),
                    ModelReplies.readLong(item, "page")));
            }
        }
        return passages;
    }

    /**
     * The tags for the chunks that were sent. Tags for anything else are the model tagging what it did not read,
     * and are dropped.
     */
    private static List<ChunkTagging> readChunkTags(final JsonObject answer, final List<String> sentChunkIds)
    {
        final List<ChunkTagging> taggings = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        if (!answer.containsKey("chunk_tags")
            || answer.get("chunk_tags").getValueType() != JsonValue.ValueType.ARRAY) {
            return taggings;
        }
        for (final JsonValue value : answer.getJsonArray("chunk_tags")) {
            if (value.getValueType() != JsonValue.ValueType.OBJECT) {
                continue;
            }
            final JsonObject item = value.asJsonObject();
            final String chunkId = ModelReplies.readString(item, "chunk_id");
            // Tags for a chunk that was not sent are the model tagging what it did not read. A chunk named
            // twice keeps its first answer, so a later contradiction cannot overwrite what it led with.
            if (chunkId != null && sentChunkIds.contains(chunkId) && seen.add(chunkId)) {
                taggings.add(new ChunkTagging(chunkId, ModelReplies.readStrings(item, "tags"),
                    ModelReplies.readConfidence(item, "confidence")));
            }
        }
        return taggings;
    }

}
