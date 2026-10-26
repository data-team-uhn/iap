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
import java.io.StringReader;
import java.io.Writer;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Stream;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.llm.CallBudget;
import io.uhndata.iap.llm.LLMCallGate;
import io.uhndata.iap.llm.LLMClient;
import io.uhndata.iap.llm.LLMConfigurationService;
import io.uhndata.iap.llm.LLMSettings;
import io.uhndata.iap.llm.internal.OpenAIClient;
import io.uhndata.iap.schemas.models.Question;

/**
 * Asks a set of documents a schema's questions through the real model, and measures the answers.
 *
 * <p>Not a unit test: it runs only when {@code extraction.dataset} names a dataset, see
 * {@code tools/dev/extraction-eval/README.md}. It goes through the same code as production. The questions are read
 * through the {@link Question} model. The calls are planned by {@link ExtractionPrompt}, made by
 * {@link DocumentReader} and sent by the LLM module's {@link OpenAIClient}.</p>
 *
 * <p>{@code summary.json} and {@code summary.md} hold counts and rates only, never a word of a document, so they can
 * be shared even when the documents cannot. {@code details.jsonl} holds every answer with its quotes, and stays
 * where it was run.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
@EnabledIfSystemProperty(named = "extraction.dataset", matches = ".+")
class ExtractionEvaluation
{
    private static final Logger LOGGER = Logger.getLogger(ExtractionEvaluation.class.getName());

    private static final String SCHEMA_PATH = "/Schemas/evaluation";

    private static final String RESOURCE_TYPE = "sling:resourceType";

    private static final String PROMPTS = "prompts.md";

    /** How much of the document {@code prompts.md} shows: enough to see where it sits, not the whole of it. */
    private static final int DOCUMENT_SHOWN = 600;

    private final SlingContext context = new SlingContext();

    @Test
    void evaluate() throws Exception
    {
        final Path dataset = Path.of(System.getProperty("extraction.dataset"));
        final boolean dryRun = Boolean.getBoolean("extraction.dryRun");
        final String model = System.getProperty("extraction.model", "Qwen3.8-27B");
        final long window = Long.getLong("extraction.contextTokens", CallBudget.DEFAULT_CONTEXT_LIMIT_TOKENS);
        final List<ExtractionPrompt.Group> groups = loadQuestions(Path.of(System.getProperty("extraction.questions",
            dataset.resolve("questions.json").toString())));
        final JsonObject expected = Files.exists(dataset.resolve("expected.json"))
            ? readJson(Files.readString(dataset.resolve("expected.json"))) : JsonValue.EMPTY_JSON_OBJECT;
        final LLMClient client = dryRun ? (system, messages, options) -> "{}" : buildClient(model, window);
        final Path results = dataset.resolve("results").resolve(
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-"
            + (dryRun ? "dry-run" : model.replaceAll("[^A-Za-z0-9.-]", "_")));
        Files.createDirectories(results);

        final EvaluationRun run = new EvaluationRun(groups, dryRun ? "dry run" : model, window);
        try (Writer details = Files.newBufferedWriter(results.resolve("details.jsonl"))) {
            for (final Path document : listDocuments(dataset.resolve("documents"))) {
                final String id = document.getFileName().toString().replaceFirst("\\.md$", "");
                final String text = Files.readString(document);
                final ExtractionPrompt.Plan plan = ExtractionPrompt.plan(text, groups, window);
                if (!Files.exists(results.resolve(PROMPTS))) {
                    writePrompts(results.resolve(PROMPTS), plan);
                }
                final long started = System.nanoTime();
                final DocumentReader.Reading reading = DocumentReader.read(client, plan, DocumentScan.of(text));
                final JsonObject wanted = expected.containsKey(id) ? expected.getJsonObject(id)
                    : JsonValue.EMPTY_JSON_OBJECT;
                details.write(run.add(id, text, plan, reading, wanted, (System.nanoTime() - started) / 1_000_000L)
                    .toString() + "\n");
                details.flush();
                LOGGER.info(() -> id + ": " + plan.calls().size() + " calls, "
                    + (plan.document().wasCut() ? "cut" : "whole"));
            }
        }
        final JsonObject summary = run.summarize();
        Files.writeString(results.resolve("summary.json"), summary.toString());
        final String table = EvaluationRun.toMarkdown(summary);
        Files.writeString(results.resolve("summary.md"), table);
        LOGGER.info(() -> table + "\nResults in " + results);
    }

    /**
     * Writes out every call the first document is asked in, exactly as it is sent but for the document, which is
     * cut short. What the prompt says can then be read without running a model.
     */
    private static void writePrompts(final Path file, final ExtractionPrompt.Plan plan) throws IOException
    {
        final StringBuilder prompts = new StringBuilder();
        for (final ExtractionPrompt.Call call : plan.calls()) {
            if (prompts.isEmpty()) {
                prompts.append("# System prompt\n\n").append(call.systemPrompt()).append("\n\n");
            }
            final String message = call.userMessage();
            final int documentEnd = message.indexOf("</document>");
            prompts.append("# Call: ").append(call.group()).append(", up to ").append(call.maxOutputTokens())
                .append(" tokens of reply\n\n").append(message, 0, Math.min(DOCUMENT_SHOWN, documentEnd))
                .append("\n[... the rest of the document ...]\n").append(message.substring(documentEnd))
                .append("\n\n## Reply schema\n\n").append(call.responseSchema()).append("\n\n");
        }
        Files.writeString(file, prompts);
    }

    /**
     * The questions a schema asks, grouped by requirement, read through the {@link Question} model the way
     * production reads them.
     */
    private List<ExtractionPrompt.Group> loadQuestions(final Path file) throws IOException
    {
        final JsonObject schema = withResourceTypes(readJson(stripComments(Files.readString(file))));
        this.context.addModelsForPackage("io.uhndata.iap.content.models", "io.uhndata.iap.entities.models",
            "io.uhndata.iap.schemas.models");
        this.context.load().json(new ByteArrayInputStream(schema.toString().getBytes(StandardCharsets.UTF_8)),
            SCHEMA_PATH);
        final Resource version = streamChildren(this.context.resourceResolver().getResource(SCHEMA_PATH))
            .filter(child -> "sch/SchemaVersion".equals(child.getResourceType())).findFirst().orElseThrow();
        final List<ExtractionPrompt.Group> groups = new ArrayList<>();
        streamChildren(version).forEach(requirement -> {
            final List<ExtractionField> fields = streamQuestions(requirement)
                .map(resource -> resource.adaptTo(Question.class))
                .filter(question -> question != null && ExtractionField.isExtractable(question))
                .map(question -> ExtractionField.of(ExtractionField.keyFor(version.getPath(), question), question))
                .toList();
            if (!fields.isEmpty()) {
                groups.add(new ExtractionPrompt.Group(requirement.getName(), fields));
            }
        });
        return groups;
    }

    private static Stream<Resource> streamChildren(final Resource resource)
    {
        final List<Resource> children = new ArrayList<>();
        resource.listChildren().forEachRemaining(children::add);
        return children.stream();
    }

    /** Every question at or under a resource, sections included, in the order they are stored. */
    private static Stream<Resource> streamQuestions(final Resource resource)
    {
        return Question.RESOURCE_TYPE.equals(resource.getResourceType()) ? Stream.of(resource)
            : streamChildren(resource).flatMap(ExtractionEvaluation::streamQuestions);
    }

    /**
     * Gives every node the resource type its node type gives it in a real repository: {@code sch:Question} becomes
     * {@code sch/Question}. The mock resolver has no node types to do it.
     */
    private static JsonObject withResourceTypes(final JsonObject node)
    {
        final JsonObjectBuilder copy = Json.createObjectBuilder();
        node.forEach((key, value) -> copy.add(key, value instanceof JsonObject
            ? withResourceTypes((JsonObject) value) : value));
        if (node.get("jcr:primaryType") instanceof JsonString && !node.containsKey(RESOURCE_TYPE)) {
            copy.add(RESOURCE_TYPE, node.getString("jcr:primaryType").replace(':', '/'));
        }
        return copy.build();
    }

    /** Initial content may carry line comments, which JSON does not allow. */
    private static String stripComments(final String json)
    {
        return String.join("\n", Arrays.stream(json.split("\n")).filter(line -> !line.strip().startsWith("//"))
            .toList());
    }

    private static JsonObject readJson(final String json)
    {
        return Json.createReader(new StringReader(json)).readObject();
    }

    private static List<Path> listDocuments(final Path folder) throws IOException
    {
        final String named = System.getProperty("extraction.documents");
        final Set<String> only = named == null ? null : Set.of(named.split(","));
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(file -> file.toString().endsWith(".md"))
                .filter(file -> only == null
                    || only.contains(file.getFileName().toString().replaceFirst("\\.md$", "")))
                .sorted().limit(Long.getLong("extraction.limit", Long.MAX_VALUE)).toList();
        }
    }

    /** The LLM module's own client, wired by hand the way the OSGi runtime wires it. */
    private static LLMClient buildClient(final String model, final long window) throws ReflectiveOperationException
    {
        final LLMSettings settings = new LLMSettings("prompter", new LLMSettings.ProviderSettings(
            System.getProperty("extraction.endpoint", "https://prompter.uhndata.io/api/proxy/v1"),
            "PROMPTER_API_KEY", Long.getLong("extraction.timeoutSeconds", 600L),
            Map.of("projectId", System.getProperty("extraction.projectId", "ia45-data"), "disableThinking", "true")),
            model, new LLMSettings.ModelSettings(window, 0.0, null, null));
        final OpenAIClient client = new OpenAIClient();
        final Method configuration = OpenAIClient.class.getDeclaredMethod("bindConfigurationService",
            LLMConfigurationService.class);
        configuration.setAccessible(true);
        configuration.invoke(client, (LLMConfigurationService) () -> settings);
        final Method gate = OpenAIClient.class.getDeclaredMethod("bindCallGate", LLMCallGate.class);
        gate.setAccessible(true);
        gate.invoke(client, (LLMCallGate) wait -> () -> { });
        return client;
    }
}
