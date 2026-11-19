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
package io.uhndata.iap.documents.generation.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.FieldOption;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.documents.generation.api.DocumentGenerator;
import io.uhndata.iap.documents.generation.api.GeneratedDocument;
import io.uhndata.iap.documents.generation.api.GenerationResult;
import io.uhndata.iap.documents.generation.api.GenerationResult.Status;
import io.uhndata.iap.documents.generation.spi.TemplateContextProvider;
import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;

/**
 * The {@link DocumentGenerator} backed by the generation worker: one synchronous {@code POST /generate} per document,
 * carrying the template and its values as JSON, answered with both files.
 *
 * <p>The worker's replies map onto the result's statuses by whose problem they are. A 422 is the template's, a 503
 * or no answer at all may pass on a retry, and anything else needs an administrator, so it is recorded through error
 * tracking as well as returned.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = DocumentGenerator.class)
@Designate(ocd = WorkerDocumentGenerator.Configuration.class)
public class WorkerDocumentGenerator implements DocumentGenerator
{
    /** Where the worker's token is read from when the configuration sets none. */
    static final String TOKEN_VARIABLE = "IAP_GENERATION_TOKEN";

    /**
     * The template size limit, in bytes. Only read from the environment, never configured here: the worker reads the
     * same variable to size its request limit, and the two must agree.
     */
    static final String MAX_TEMPLATE_BYTES_VARIABLE = "IAP_MAX_TEMPLATE_BYTES";

    static final String DEFAULT_WORKER_URL = "http://localhost:18766";

    /** Above the worker's own worst case: three LibreOffice runs of 10 seconds each. */
    static final int DEFAULT_RESPONSE_TIMEOUT = 40;

    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerDocumentGenerator.class);

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** How much of a reply that is not the worker's usual JSON is kept in the message. */
    private static final int ERROR_EXCERPT_LENGTH = 200;

    /**
     * Where the worker is, and how to reach it.
     *
     * @since 0.1.0
     */
    @ObjectClassDefinition(name = "Document generation worker",
        description = "Where IAP sends .docx templates to be filled in and converted to PDF.")
    public @interface Configuration
    {
        /**
         * The worker's base URL.
         *
         * @return a URL without a trailing slash
         */
        @AttributeDefinition(name = "Worker URL",
            description = "The generation worker's address, e.g. http://generation:18766 in Docker Compose.")
        String workerUrl() default DEFAULT_WORKER_URL;

        /**
         * The worker's bearer token.
         *
         * @return a token, or empty to read the environment
         */
        @AttributeDefinition(name = "Worker token", type = AttributeType.PASSWORD,
            description = "The token the worker requires, if it requires one. Leave empty to use the "
                + TOKEN_VARIABLE + " environment variable, or no token if that is unset too.")
        String workerToken() default "";

        /**
         * How long to wait for both documents.
         *
         * @return seconds
         */
        @AttributeDefinition(name = "Response timeout",
            description = "Seconds to wait for a generated document. A one-page letter takes about a second, and "
                + "the worker gives LibreOffice at most 30.")
        int responseTimeout() default DEFAULT_RESPONSE_TIMEOUT;
    }

    // Re-read on every use, as a provider can come or go between two generations
    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC,
        fieldOption = FieldOption.REPLACE)
    private volatile List<TemplateContextProvider> providers;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    private String workerUrl;

    private Duration responseTimeout;

    /** The {@code Authorization} header to send, or {@code null} when the worker needs none. */
    private String authorization;

    private volatile int maxTemplateBytes;

    /**
     * Read the configuration, falling back to the environment for the token, and the template size limit.
     *
     * @param configuration the component configuration
     */
    @Activate
    @Modified
    protected void activate(final Configuration configuration)
    {
        final String url = configuration.workerUrl() == null ? "" : configuration.workerUrl().trim();
        this.workerUrl = (url.isEmpty() ? DEFAULT_WORKER_URL : url).replaceAll("/+$", "");
        this.responseTimeout = Duration.ofSeconds(configuration.responseTimeout() > 0
            ? configuration.responseTimeout() : DEFAULT_RESPONSE_TIMEOUT);
        final String configured = configuration.workerToken() == null ? "" : configuration.workerToken().trim();
        final String fromEnvironment = environment(TOKEN_VARIABLE);
        final String token = configured.isEmpty() && fromEnvironment != null ? fromEnvironment.trim() : configured;
        this.authorization = token.isEmpty() ? null : "Bearer " + token;
        this.maxTemplateBytes = maxTemplateBytes(environment(MAX_TEMPLATE_BYTES_VARIABLE));
    }

    @Override
    public int getMaxTemplateBytes()
    {
        return this.maxTemplateBytes;
    }

    /**
     * The template size limit the environment sets, or the default.
     *
     * @param configured the variable's value, or {@code null} when it is not set
     * @return a positive size in bytes
     */
    private static int maxTemplateBytes(final String configured)
    {
        final String value = configured == null ? "" : configured.trim();
        if (value.isEmpty()) {
            return DEFAULT_MAX_TEMPLATE_BYTES;
        }
        // Ten digits at most, so that parsing cannot fail and the range check below is all that is left
        if (value.matches("\\d{1,10}")) {
            final long parsed = Long.parseLong(value);
            if (parsed > 0 && parsed <= Integer.MAX_VALUE) {
                return (int) parsed;
            }
        }
        LOGGER.warn("{}={} is not a positive number of bytes; using {}", MAX_TEMPLATE_BYTES_VARIABLE, configured,
            DEFAULT_MAX_TEMPLATE_BYTES);
        return DEFAULT_MAX_TEMPLATE_BYTES;
    }

    @Override
    public GenerationResult generate(final Resource template, final Resource target)
    {
        final byte[] content;
        try {
            content = read(template);
        } catch (final IOException e) {
            return failure(template, target, "The template at " + template.getPath() + " could not be read: "
                + describe(e));
        }
        final String problem = problem(template, content);
        if (problem != null) {
            return GenerationResult.failed(Status.TEMPLATE_ERROR, problem);
        }
        final JsonObject values;
        try {
            values = values(target);
        } catch (final ValuesException e) {
            return failure(template, target, e.getMessage());
        }

        // Ask the worker
        return ask(template, target, Json.createObjectBuilder()
            .add("template", Base64.getEncoder().encodeToString(content))
            .add("context", values)
            .build()
            .toString());
    }

    /**
     * What is wrong with the template's content before the worker is even asked.
     *
     * @param template the template resource, to name it
     * @param content what {@link #read} found
     * @return the problem, or {@code null} when there is none
     */
    private String problem(final Resource template, final byte[] content)
    {
        if (content == null) {
            return "The template at " + template.getPath() + " is not a file";
        }
        if (content.length > this.maxTemplateBytes) {
            return "The template at " + template.getPath() + " is over the " + this.maxTemplateBytes + "-byte limit";
        }
        return null;
    }

    private GenerationResult ask(final Resource template, final Resource target, final String body)
    {
        final long startedAt = System.nanoTime();
        final HttpResponse<String> response;
        try {
            response = send(request(body));
        } catch (final HttpTimeoutException e) {
            return unavailable(template, "The generation worker did not answer within "
                + this.responseTimeout.toSeconds() + " seconds");
        } catch (final IOException e) {
            return unavailable(template, "The generation worker at " + this.workerUrl + " could not be reached: "
                + describe(e));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return unavailable(template, "Interrupted while waiting for the generation worker");
        }
        return answer(template, target, response, startedAt);
    }

    /** Map the worker's reply onto a result, by whose problem it is. */
    private GenerationResult answer(final Resource template, final Resource target,
        final HttpResponse<String> response, final long startedAt)
    {
        return switch (response.statusCode()) {
            case 200 -> documents(template, target, response.body(), startedAt);
            case 422 -> GenerationResult.failed(Status.TEMPLATE_ERROR, error(response.body()));
            case 503 -> unavailable(template, "The generation worker is unavailable: " + error(response.body()));
            default -> failure(template, target,
                "The generation worker answered HTTP " + response.statusCode() + ": " + error(response.body()));
        };
    }

    /**
     * Actually send the request. Overridable so that a test can stand in for the worker.
     *
     * @param request the request to send
     * @return the raw response
     * @throws IOException if the worker could not be reached, or did not answer in time
     * @throws InterruptedException if the calling thread was interrupted while waiting for the response
     */
    protected HttpResponse<String> send(final HttpRequest request) throws IOException, InterruptedException
    {
        return this.client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Read an environment variable. Overridable so that tests can supply one without controlling the real
     * environment.
     *
     * @param name the variable to read
     * @return the value, or {@code null} when not set
     */
    protected String environment(final String name)
    {
        return System.getenv(name);
    }

    private HttpRequest request(final String body)
    {
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(this.workerUrl + "/generate"))
            .timeout(this.responseTimeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (this.authorization != null) {
            request.header("Authorization", this.authorization);
        }
        return request.build();
    }

    /**
     * The template's bytes, reading one more than the limit so that an oversized one can be told apart.
     *
     * @param template the template resource
     * @return the bytes read, or {@code null} when the resource has no file content
     * @throws IOException if the content cannot be read
     */
    private byte[] read(final Resource template) throws IOException
    {
        try (InputStream content = template.adaptTo(InputStream.class)) {
            // Long arithmetic: a limit of Integer.MAX_VALUE plus one would wrap negative
            return content == null ? null : content.readNBytes((int) Math.min(this.maxTemplateBytes + 1L,
                Integer.MAX_VALUE));
        }
    }

    /**
     * Every provider's value for the target, each under its own name.
     *
     * @param target what the document is about
     * @return the values, as the worker's {@code context}
     * @throws ValuesException when a provider fails, or supplies something a template cannot hold
     */
    private JsonObject values(final Resource target) throws ValuesException
    {
        final JsonObjectBuilder values = Json.createObjectBuilder();
        final List<TemplateContextProvider> current = this.providers;
        if (current == null) {
            return values.build();
        }

        // Gather each name once; the first provider to claim one keeps it
        final Set<String> names = new HashSet<>();
        for (final TemplateContextProvider provider : current) {
            final String name = provider.getName();
            if (!names.add(name)) {
                LOGGER.warn("Ignoring {}: another template value provider already supplies \"{}\"",
                    provider.getClass().getName(), name);
                continue;
            }
            try {
                final Object value = provider.getValue(target);
                if (value != null) {
                    values.add(name, TemplateValues.toJson(value, name));
                }
            } catch (final RuntimeException e) {
                throw new ValuesException("The \"" + name + "\" template values could not be gathered: "
                    + describe(e), e);
            }
        }
        return values.build();
    }

    private GenerationResult documents(final Resource template, final Resource target, final String body,
        final long startedAt)
    {
        final GeneratedDocument document;
        final int pdfBytes;
        try (JsonReader reader = Json.createReader(new StringReader(body))) {
            final JsonObject reply = reader.readObject();
            final String docx = reply.getString("docx", null);
            final String pdf = reply.getString("pdf", null);
            if (docx == null || pdf == null) {
                return failure(template, target, "The generation worker's answer is missing a document");
            }
            final byte[] decodedPdf = Base64.getDecoder().decode(pdf);
            pdfBytes = decodedPdf.length;
            document = new GeneratedDocument(Base64.getDecoder().decode(docx), decodedPdf);
        } catch (final JsonException | ClassCastException | IllegalArgumentException e) {
            // Not JSON, or a document that is not base64
            return failure(template, target, "The generation worker's answer could not be read: " + describe(e));
        }
        LOGGER.info("Generated a document for {} from {}: pdfBytes={} inMs={}", target.getPath(),
            template.getPath(), pdfBytes, (System.nanoTime() - startedAt) / 1_000_000L);
        return GenerationResult.generated(document);
    }

    private GenerationResult unavailable(final Resource template, final String message)
    {
        LOGGER.warn("Generation from {} is unavailable: {}", template.getPath(), message);
        return GenerationResult.failed(Status.UNAVAILABLE, message);
    }

    /** Record a failure an administrator has to look at, and return it. */
    private static GenerationResult failure(final Resource template, final Resource target, final String message)
    {
        LOGGER.error("Generation from {} for {} failed: {}", template.getPath(), target.getPath(), message);
        ErrorLogger.logProblem(message, ErrorContext.of(WorkerDocumentGenerator.class, "generate")
            .about(target)
            .with("template", template.getPath()));
        return GenerationResult.failed(Status.FAILED, message);
    }

    /**
     * The worker's own error message, falling back to an excerpt of the raw body when it is not the usual
     * {@code {"error": "..."}}. A 500 also carries the reference its details are logged under in the worker.
     *
     * @param body the response body
     * @return a message
     */
    private static String error(final String body)
    {
        if (body == null || body.isBlank()) {
            return "(empty response)";
        }
        try (JsonReader reader = Json.createReader(new StringReader(body))) {
            final JsonObject reply = reader.readObject();
            final String error = reply.getString("error", null);
            if (error != null) {
                final String reference = reply.getString("reference", null);
                return reference == null ? error : error + " (worker log reference " + reference + ")";
            }
        } catch (final JsonException | ClassCastException e) {
            // Not JSON, fall through to the raw excerpt
        }
        return body.length() > ERROR_EXCERPT_LENGTH ? body.substring(0, ERROR_EXCERPT_LENGTH) + "…" : body;
    }

    /**
     * What went wrong. A connection that never opened comes as an exception with no message at all.
     *
     * @param failure what was thrown
     * @return the message, or the exception type when there is none
     */
    private static String describe(final Exception failure)
    {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    /**
     * A provider failed, or supplied a value no template can hold.
     *
     * @version $Id$
     * @since 0.1.0
     */
    private static final class ValuesException extends Exception
    {
        private static final long serialVersionUID = 1L;

        ValuesException(final String message, final Throwable cause)
        {
            super(message, cause);
        }
    }
}
