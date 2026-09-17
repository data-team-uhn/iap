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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.documents.api.ParseOutcome;
import io.uhndata.iap.documents.spi.ParseOutcomeHandler;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;

/**
 * Turns a finished parse into a {@code documentParsed} event on the submission the file belongs to. The system
 * workflow catching it reads the parse in and, once every file has been parsed, queues the reading of the
 * answers - so what happens after a parse lands is content, and this only knocks on the engine's door.
 *
 * <p>The event is fired as this module's own service user, which the {@code documentParsed} start event names as
 * its one performer: the payload carries paths on the shared volume, and nobody else should be able to have them
 * read into the repository.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ParseOutcomeHandler.class)
public class ParseCompletionHandler implements ParseOutcomeHandler
{
    /** The event a finished parse fires on the submission. */
    static final String EVENT = "documentParsed";

    /** The subservice this module reads submissions as. */
    static final String SUBSERVICE = "extraction";

    /** The payload entry naming the {@code sub:File} the parse was for. */
    static final String FILE = "file";

    /** The payload entry saying whether the daemon produced anything. */
    static final String SUCCEEDED = "succeeded";

    /** The payload entry carrying what went wrong. */
    static final String ERROR = "error";

    /** The payload entry naming the Markdown on the shared volume. */
    static final String MARKDOWN = "markdown";

    /** The payload entry naming the PDF rendition beside the Markdown, when the daemon left one. */
    static final String PDF = "pdf";

    /** The payload entry naming the chunk tree on the shared volume. */
    static final String CHUNKS = "chunks";

    private static final Logger LOGGER = LoggerFactory.getLogger(ParseCompletionHandler.class);

    private static final String PDF_SUFFIX = ".pdf";

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private WorkflowEngine engine;

    @Override
    public boolean handle(final ParseOutcome outcome)
    {
        try (ResourceResolver resolver = this.resolverFactory.getServiceResourceResolver(
            Map.of(ResourceResolverFactory.SUBSERVICE, SUBSERVICE))) {
            final Resource file = resolver.getResource(outcome.target());
            final Resource submission = file == null ? null : SubmissionFiles.submissionOf(file);
            if (submission == null) {
                // Nothing left to record it on, so nothing left to keep the record for either
                LOGGER.warn("Parse job {} was for {}, which is no longer part of a submission", outcome.jobId(),
                    outcome.target());
                return true;
            }
            this.engine.receiveEvent(submission, new WorkflowEvent(EVENT, payload(outcome)));
            return true;
        } catch (final LoginException e) {
            LOGGER.error("Cannot read submissions to record parse job {}: {}", outcome.jobId(), e.getMessage(), e);
            return false;
        } catch (final WorkflowException e) {
            LOGGER.error("Recording parse job {} on {} failed: {}", outcome.jobId(), outcome.target(),
                e.getMessage(), e);
            return false;
        }
    }

    /**
     * What the event carries: the outcome as it was, plus the PDF rendition when the daemon left one beside the
     * Markdown, which the callback does not mention.
     *
     * @param outcome how the parse ended
     * @return the payload
     */
    static Map<String, Object> payload(final ParseOutcome outcome)
    {
        final Map<String, Object> payload = new HashMap<>();
        payload.put(FILE, outcome.target());
        payload.put(SUCCEEDED, outcome.succeeded());
        putIfPresent(payload, ERROR, outcome.error());
        putIfPresent(payload, MARKDOWN, outcome.markdownPath());
        putIfPresent(payload, CHUNKS, outcome.chunksPath());
        if (outcome.markdownPath() != null) {
            final Path markdown = Path.of(outcome.markdownPath());
            final String name = String.valueOf(markdown.getFileName());
            final Path pdf = markdown.resolveSibling(name.replaceFirst("\\.[^.]*$", "") + PDF_SUFFIX);
            if (Files.exists(pdf)) {
                payload.put(PDF, pdf.toString());
            }
        }
        return payload;
    }

    private static void putIfPresent(final Map<String, Object> payload, final String key, final String value)
    {
        if (value != null) {
            payload.put(key, value);
        }
    }
}
