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
 * workflow catching it reads the parse in and queues the reading of the answers - so what happens after a parse
 * lands is content, and this only knocks on the engine's door.
 *
 * <p>Clearing the staging folder is this side's, and only once the engine has committed: until then the volume
 * holds the only copy of what the parse produced.</p>
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

    /** The payload entry holding how large the daemon measured the document to be, in tokens. */
    static final String TOKENS = "tokens";

    private static final Logger LOGGER = LoggerFactory.getLogger(ParseCompletionHandler.class);

    private static final String PDF_SUFFIX = ".pdf";

    /** The extension of a path's last segment, which is what the PDF beside it replaces. */
    private static final String LAST_EXTENSION = "\\.[^.\\\\/]*$";

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private WorkflowEngine engine;

    @Reference
    private ParseResultIngester ingester;

    @Override
    public boolean handle(final ParseOutcome outcome)
    {
        try (ResourceResolver resolver = this.resolverFactory.getServiceResourceResolver(
            Map.of(ResourceResolverFactory.SUBSERVICE, SUBSERVICE))) {
            final Resource file = resolver.getResource(outcome.target());
            final Resource submission = file == null ? null : SubmissionFiles.submissionOf(file);
            if (submission == null) {
                // Nothing left to record it on, so nothing left to keep the record for either. The volume
                // still has to be cleared: the ingest step that normally does it never runs now.
                LOGGER.warn("Parse job {} was for {}, which is no longer part of a submission", outcome.jobId(),
                    outcome.target());
                this.ingester.discardStaging(
                    outcome.markdownPath() == null ? null : Path.of(outcome.markdownPath()));
                return true;
            }
            LOGGER.info("Parse came back: job={} file={} submission={} succeeded={} tokens={}", outcome.jobId(),
                outcome.target(), submission.getPath(), outcome.succeeded(), outcome.tokens());
            final String staged = stagedFolder(outcome, file);
            this.engine.receiveEvent(submission, new WorkflowEvent(EVENT, payload(outcome)));
            // Only now the repository has committed what the parse produced. Until this point the volume holds
            // the only copy, and a delete before the commit would lose the parse for good if the commit failed.
            this.ingester.discardStaging(staged == null ? null : Path.of(staged));
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
     * A path inside the folder the parse worked in, so the whole of it can be cleared afterwards. The Markdown
     * when the parse produced one; failing that, where the upload was staged, which is the same folder and is all
     * a failed parse leaves to go on.
     *
     * @param outcome how the parse ended
     * @param file the {@code sub:File} the parse was for
     * @return a path inside the staging folder, or {@code null} when nothing names one
     */
    private static String stagedFolder(final ParseOutcome outcome, final Resource file)
    {
        if (outcome.markdownPath() != null) {
            return outcome.markdownPath();
        }
        final String staged = file.getValueMap().get(ParseDocumentsHandler.SHARED_PATH, String.class);
        return staged == null || staged.isBlank() ? null : staged;
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
        putIfPresent(payload, TOKENS, outcome.tokens());
        if (outcome.markdownPath() != null) {
            putIfPresent(payload, PDF, renditionBeside(Path.of(outcome.markdownPath())));
        }
        return payload;
    }

    /**
     * The PDF the daemon left beside the Markdown, under the same name. Its own convention, which the callback
     * does not repeat, so the only way to know is to look.
     *
     * @param markdown where the Markdown is
     * @return the PDF's path, or {@code null} when there is none beside it
     */
    private static String renditionBeside(final Path markdown)
    {
        // The extension of the last segment, so a dot in a folder name is left where it is
        final Path pdf = Path.of(markdown.toString().replaceFirst(LAST_EXTENSION, "") + PDF_SUFFIX);
        return Files.exists(pdf) ? pdf.toString() : null;
    }

    private static void putIfPresent(final Map<String, Object> payload, final String key, final Object value)
    {
        if (value != null) {
            payload.put(key, value);
        }
    }
}
