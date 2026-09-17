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
import java.util.List;
import java.util.Set;

import jakarta.json.JsonObject;

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
 * A second, closer look at what kind of proposal a document is, for when the gate could not tell from its
 * opening. Reads the parts that say what the study does - background, objectives, design - up to the active
 * model's whole-document budget, and asks one thing: which category.
 *
 * <p>Like the gate, it does not guess. An answer that names no category that exists, that cannot be read twice
 * over, or that cannot be had at all is no pick, and the choice is left to a person.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ProposalCategoryService.class)
public class ProposalCategoryService
{
    /** Below this, the gate's pick is not trusted and the document gets this second look. */
    static final double CONFIDENCE_FLOOR = 0.75;

    /** The rubrics that say what a study does: background, objectives, design. */
    static final Set<String> DESIGN_TAGS = Set.of("B.1", "B.2", "B.3");

    private static final Logger LOGGER = LoggerFactory.getLogger(ProposalCategoryService.class);

    /** The name the provider associates with the response schema. */
    private static final String SCHEMA_NAME = "iap_proposal_category";

    /** Room for one category, a confidence and a short reason. */
    private static final long MAX_OUTPUT_TOKENS = 300L;

    /** The rubric reference this call opens with, which the prompt calls PROTOCOL_STRUCTURE_GLOSSARY. */
    private static final String GLOSSARY_HEADER = "## PROTOCOL_STRUCTURE_GLOSSARY";

    private static final String CATALOG_HEADER = "## CATALOG (chunk id: heading) (untrusted data)";

    private static final String CHUNK_HEADER = "## CHUNK (untrusted data)";

    private static final String BREAK = "\n\n";

    private static final String CORRECTION =
        "\n\n# Correction\n\nYour previous answer was not a single JSON object matching the schema. "
            + "Answer again with exactly one such object and nothing else.";

    @Reference
    private LLMClientFactory llmClientFactory;

    @Reference
    private LLMConfigurationService configurationService;

    /**
     * Whether the gate's pick is not to be trusted: the document is a proposal, and the gate either picked no
     * category or was not sure enough of the one it picked.
     *
     * @param decision what the gate decided
     * @return {@code true} when the document should get this second look
     */
    public boolean needsSecondLook(final ProposalGateService.GateDecision decision)
    {
        if (decision.verdict() != ProposalGateService.Verdict.PROPOSAL) {
            return false;
        }
        final CategoryPick pick = decision.category();
        return pick == null || pick.confidence() < CONFIDENCE_FLOOR;
    }

    /**
     * Read the parts of a proposal that say what the study does, and ask which category it belongs under.
     *
     * @param file the parsed file
     * @param categories the categories it may be filed under, see {@link CategoryCatalog#read}
     * @return the pick, or {@code null} when none could safely be made
     * @throws IOException if the document cannot be read
     */
    public CategoryPick classify(final File file, final List<CategoryCatalog.Entry> categories) throws IOException
    {
        if (categories.isEmpty()) {
            LOGGER.warn("There are no categories to file {} under", file.getPath());
            return null;
        }
        final String userMessage = buildUserMessage(file, readTokenBudget());
        if (userMessage == null) {
            LOGGER.warn("Nothing could be shown to place {} under a category", file.getPath());
            return null;
        }
        try {
            return ask(userMessage, categories);
        } catch (IOException e) {
            LOGGER.warn("Could not ask what kind of proposal {} is: {}", file.getPath(), e.getMessage());
            return null;
        }
    }

    /**
     * The blocks about this document: the outline, and the text of the chunks worth reading for this. A
     * document that was never chunked is sent whole, up to the budget.
     *
     * @param file the parsed file
     * @param budget how many tokens of text may be sent
     * @return the user message, or {@code null} when there is no text to show
     * @throws IOException if the document cannot be read
     */
    private static String buildUserMessage(final File file, final long budget) throws IOException
    {
        final Chunks holder = file.getChunks();
        final List<Chunk> chunks = holder == null ? List.of() : holder.getChunks();
        if (chunks.isEmpty()) {
            return describeWholeDocument(file, budget);
        }
        final List<ChunkCatalog.Entry> entries = ChunkCatalog.read(chunks);
        final Set<String> sent = FullTextSelection.select(entries, DESIGN_TAGS, budget);
        final StringBuilder catalog = new StringBuilder(CATALOG_HEADER).append(BREAK);
        final StringBuilder text = new StringBuilder();
        for (final ChunkCatalog.Entry entry : entries) {
            catalog.append(entry.getName()).append(": ").append(entry.describeHeadings()).append('\n');
            if (sent.contains(entry.getName()) && entry.text() != null && !entry.text().isBlank()) {
                text.append("[chunk:").append(entry.getName()).append("]\n").append(entry.text().strip())
                    .append(BREAK);
            }
        }
        if (text.isEmpty()) {
            return null;
        }
        return catalog.toString().strip() + BREAK + CHUNK_HEADER + BREAK + text.toString().strip();
    }

    private static String describeWholeDocument(final File file, final long budget) throws IOException
    {
        final String markdown = ChunkContent.readText(file.getFileMarkdown());
        final int limit = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, budget) * ChunkContent.CHARS_PER_TOKEN);
        final String text = markdown.length() <= limit ? markdown : markdown.substring(0, limit);
        if (text.isBlank()) {
            return null;
        }
        return CHUNK_HEADER + BREAK + "[chunk:" + IntakePayload.WHOLE_DOCUMENT_ID + "]\n" + text.strip();
    }

    /**
     * How much text may be sent: the active model's whole-document limit, the same budget the intake reads
     * under, or the default when the settings cannot be read.
     */
    private long readTokenBudget()
    {
        try {
            return this.configurationService.getActiveSettings().getWholeDocumentTokenLimit();
        } catch (IOException e) {
            LOGGER.warn("Could not read the active LLM settings for the category budget: {}", e.getMessage());
            return LLMSettings.DEFAULT_WHOLE_DOCUMENT_TOKEN_LIMIT;
        }
    }

    /**
     * Put the question to the model, once, and once more if the first answer was not the shape it had to be.
     *
     * <p>The system prompt opens with the glossary, the same bytes the intake opens with, so the two share a
     * cached prefix; the categories come next, the same on every call until an administrator edits them.
     *
     * @param userMessage what to show the model
     * @param categories the categories to choose from
     * @return the pick, or {@code null} when the answer named none or could not be read twice
     * @throws IOException if the model cannot be reached
     */
    private CategoryPick ask(final String userMessage, final List<CategoryCatalog.Entry> categories)
        throws IOException
    {
        final LLMClient client = this.llmClientFactory.getActiveClient();
        final String system = GLOSSARY_HEADER + BREAK + Prompts.read(Prompts.PROTOCOL_STRUCTURE_GLOSSARY).strip()
            + BREAK + CategoryCatalog.describe(categories) + BREAK + Prompts.read(Prompts.CLASSIFY_SYSTEM);
        final LLMRequestOptions options = LLMRequestOptions.builder()
            .maxOutputTokens(MAX_OUTPUT_TOKENS)
            .jsonSchema(SCHEMA_NAME, ResponseSchemas.classify(categories))
            .build();

        JsonObject answer = readAnswer(client.chat(system, List.of(new LLMMessage("user", userMessage)), options));
        if (answer == null) {
            LOGGER.warn("The category was not answered in the required shape; asking once more");
            answer = readAnswer(client.chat(system,
                List.of(new LLMMessage("user", userMessage + CORRECTION)), options));
        }
        if (answer == null) {
            LOGGER.warn("The category was not answered in the required shape twice; leaving it open");
            return null;
        }
        final CategoryCatalog.Entry entry =
            CategoryCatalog.find(categories, ModelReplies.readString(answer, "category"));
        return entry == null ? null : new CategoryPick(entry.path(), ModelReplies.readConfidence(answer, "confidence"));
    }

    /**
     * The model's answer as JSON, when it is the shape it had to be.
     *
     * @param reply what the model said
     * @return the answer, or {@code null} when it cannot be read
     */
    private static JsonObject readAnswer(final String reply)
    {
        if (reply == null || reply.isBlank()) {
            return null;
        }
        final JsonObject answer = ModelReplies.readJsonObject(reply);
        return answer == null || !answer.containsKey("category") ? null : answer;
    }
}
