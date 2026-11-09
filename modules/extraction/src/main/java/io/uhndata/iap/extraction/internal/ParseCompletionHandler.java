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
import java.util.function.BooleanSupplier;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.documents.api.ParseOutcome;
import io.uhndata.iap.documents.spi.ParseOutcomeHandler;
import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.workflows.api.WorkflowEngine;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;

/**
 * Turns a finished parse into a {@code documentParsed} event on the submission the file belongs to. The
 * {@code documentParsed} system workflow then reads the parse in and queues the reading of the answers.
 *
 * <p>This class deletes the staging folder, but only after the engine has committed: until then the shared volume
 * holds the only copy of what the parse produced.</p>
 *
 * <p>The event is fired as this module's service user, the only performer the {@code documentParsed} start event
 * allows. The payload holds paths on the shared volume, and no one else should be able to have those read into
 * the repository.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ParseOutcomeHandler.class)
public class ParseCompletionHandler implements ParseOutcomeHandler
{
    /** The event a finished parse fires on the submission. */
    static final String DOCUMENT_PARSED_EVENT = "documentParsed";

    /** The subservice name this module logs in with to read submissions. */
    static final String SUBSERVICE_NAME = "extraction";

    /** The payload entry with the path of the {@code sub:File} the parse was for. */
    static final String FILE_KEY = "file";

    /** The payload entry saying whether the parse succeeded. */
    static final String SUCCEEDED_KEY = "succeeded";

    /** The payload entry with the error message of a failed parse. */
    static final String ERROR_KEY = "error";

    /** The payload entry with the path of the Markdown on the shared volume. */
    static final String MARKDOWN_KEY = "markdown";

    /** The payload entry with the path of the PDF next to the Markdown, when the daemon made one. */
    static final String PDF_KEY = "pdf";

    /** The payload entry with the size of the document in tokens, as the daemon measured it. */
    static final String TOKENS_KEY = "tokens";

    /** How many times to look for the file to name the outcome's job, half a second apart. */
    static final int COMMIT_WAIT_LOOKS = 10;

    private static final Logger LOGGER = LoggerFactory.getLogger(ParseCompletionHandler.class);

    private static final String PDF_SUFFIX = ".pdf";

    private static final long COMMIT_WAIT_MILLISECONDS = 500L;

    /** Matches the extension of a path's last segment, which the PDF path replaces. */
    private static final String LAST_EXTENSION = "\\.[^.\\\\/]*$";

    /** Waits between two looks at the file, then says whether to look again; tests replace it. */
    private BooleanSupplier pause = () -> pause(COMMIT_WAIT_MILLISECONDS);

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
            Map.of(ResourceResolverFactory.SUBSERVICE, SUBSERVICE_NAME))) {
            final Resource file = findFile(resolver, outcome);
            final Resource submission = file == null ? null : SubmissionFiles.findSubmission(file);
            final ValueMap recorded = file == null ? ValueMap.EMPTY : file.getValueMap();
            if (submission == null || !isWaitingFor(file, outcome)) {
                // Not taken: the file may just not be committed yet, and the sweep hands the outcome over again.
                // A job record nobody takes is dropped with its staging folder later.
                LOGGER.warn("Parse job {} is not what {} is waiting for, or the file is gone", outcome.jobId(),
                    outcome.target());
                return false;
            }
            final String parseStatus = recorded.get(ParsePropertyNames.PARSE_STATUS_PROPERTY, String.class);
            if (!ParsePropertyNames.STATUS_QUEUED.equals(parseStatus)) {
                // Already recorded: the sweep handed the same outcome over again because its folder was not deleted
                return discardStaging(getStagedPath(outcome, file));
            }
            LOGGER.info("Parse came back: job={} file={} submission={} succeeded={} tokens={}", outcome.jobId(),
                outcome.target(), submission.getPath(), outcome.succeeded(), outcome.tokens());
            if (!outcome.succeeded()) {
                // The submitter is only told to try again; why it failed is for an administrator
                LOGGER.warn("Parse job {} for {} failed: {}", outcome.jobId(), outcome.target(), outcome.error());
                ErrorLogger.logProblem("A document could not be parsed",
                    ErrorContext.of(ParseCompletionHandler.class, "handle").about(outcome.target())
                        .with("job", outcome.jobId()).with("error", outcome.error()));
            }
            final String staged = getStagedPath(outcome, file);
            this.engine.receiveEvent(submission, new WorkflowEvent(DOCUMENT_PARSED_EVENT, buildPayload(outcome)));
            // Delete the folder only now that the parse is committed: deleted earlier, a failed commit would lose
            // the parse. If the delete fails, the job record stays, so the sweep tries again.
            return discardStaging(staged);
        } catch (final LoginException e) {
            LOGGER.error("Cannot read submissions to record parse job {}: {}", outcome.jobId(), e.getMessage(), e);
            // The service user is missing: no parse can be read in until a deployment fault is fixed
            ErrorLogger.logError(e, ErrorContext.of(ParseCompletionHandler.class, "openSession")
                .about(outcome.target()).with("job", outcome.jobId()));
            return false;
        } catch (final WorkflowException e) {
            LOGGER.error("Recording parse job {} on {} failed: {}", outcome.jobId(), outcome.target(),
                e.getMessage(), e);
            ErrorLogger.logError(e, ErrorContext.of(ParseCompletionHandler.class, "recordParse")
                .about(outcome.target()).with("job", outcome.jobId()));
            return false;
        }
    }

    /**
     * Finds the file an outcome is for, waiting a few seconds for it to name the outcome's job. The walk that
     * queues a parse commits after the job is queued, so a fast outcome, such as from a daemon that cannot be
     * reached, can arrive before the file names its job. Looking again here is faster than waiting for the sweep.
     * A stopped parse is not waited for: the stop took the job off the file, so it would never name it again.
     */
    private Resource findFile(final ResourceResolver resolver, final ParseOutcome outcome)
    {
        Resource file = resolver.getResource(outcome.target());
        for (int look = 1; look < COMMIT_WAIT_LOOKS && file != null && !isDoneWaiting(file, outcome)
            && this.pause.getAsBoolean(); look++) {
            resolver.refresh();
            file = resolver.getResource(outcome.target());
        }
        return file;
    }

    /** Whether there is nothing more to wait for: the file names the job, or the parse was stopped. */
    private static boolean isDoneWaiting(final Resource file, final ParseOutcome outcome)
    {
        return outcome.isStopped() || isWaitingFor(file, outcome);
    }

    private static boolean isWaitingFor(final Resource file, final ParseOutcome outcome)
    {
        return outcome.jobId()
            .equals(file.getValueMap().get(ParseDocumentsHandler.PARSE_JOB_ID_PROPERTY, String.class));
    }

    /**
     * Replaces the wait between two looks at a file, so a test needs no real time.
     *
     * @param pause waits, then says whether to look again
     */
    void setPause(final BooleanSupplier pause)
    {
        this.pause = pause;
    }

    /**
     * Returns the wait between two looks at a file.
     *
     * @return the wait, which then says whether to look again
     */
    BooleanSupplier getPause()
    {
        return this.pause;
    }

    /** Sleeps, and returns {@code false} when interrupted, which ends the wait. */
    private static boolean pause(final long milliseconds)
    {
        try {
            Thread.sleep(milliseconds);
            return true;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Deletes the staging folder, and returns whether it is gone. */
    private boolean discardStaging(final String staged)
    {
        return this.ingester.discardStaging(staged == null ? null : Path.of(staged));
    }

    /**
     * Returns a path inside the folder the parse worked in, so the whole folder can be deleted afterwards. That is
     * the Markdown when the parse made one, else the staged upload, which is in the same folder.
     *
     * @param outcome how the parse ended
     * @param file the {@code sub:File} the parse was for
     * @return a path inside the staging folder, or {@code null} when neither is known
     */
    private static String getStagedPath(final ParseOutcome outcome, final Resource file)
    {
        if (outcome.markdownPath() != null) {
            return outcome.markdownPath();
        }
        final String staged = file.getValueMap().get(ParseDocumentsHandler.SHARED_PATH_PROPERTY, String.class);
        return staged == null || staged.isBlank() ? null : staged;
    }

    /**
     * Builds the event payload: the outcome as it came, plus the PDF next to the Markdown when the daemon made
     * one, since the callback does not mention it.
     *
     * @param outcome how the parse ended
     * @return the payload; entries with no value are left out
     */
    static Map<String, Object> buildPayload(final ParseOutcome outcome)
    {
        final Map<String, Object> payload = new HashMap<>();
        payload.put(FILE_KEY, outcome.target());
        payload.put(SUCCEEDED_KEY, outcome.succeeded());
        putIfPresent(payload, ERROR_KEY, outcome.error());
        putIfPresent(payload, MARKDOWN_KEY, outcome.markdownPath());
        putIfPresent(payload, TOKENS_KEY, outcome.tokens());
        if (outcome.markdownPath() != null) {
            putIfPresent(payload, PDF_KEY, findPdfBeside(Path.of(outcome.markdownPath())));
        }
        return payload;
    }

    /**
     * Finds the PDF the daemon left next to the Markdown, with the same name. The callback does not report it, so
     * the only way to know is to look.
     *
     * @param markdown the path of the Markdown
     * @return the PDF's path, or {@code null} when there is none
     */
    private static String findPdfBeside(final Path markdown)
    {
        // Only the last segment's extension, so a dot in a folder name stays
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
