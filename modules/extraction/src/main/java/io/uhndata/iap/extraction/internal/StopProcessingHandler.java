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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.event.jobs.JobManager;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.documents.api.ParseControl;
import io.uhndata.iap.documents.api.ParseService;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that stops a reading still in progress, and does nothing when no reading is running. A parse
 * that has not come back is abandoned: the daemon is told, and the job record is marked failed for the sweep to
 * remove with its staging folder. A running reading is told to stop, and the Markdown its current step was
 * reading is deleted, so the next try parses the upload again instead of reusing that text. Reading jobs still
 * waiting in the queue are removed.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class StopProcessingHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "stopProcessing";

    /** The topics of the jobs that read a submission, queued ones of which a Stop removes. */
    private static final List<String> READING_TOPICS =
        List.of(ExtractAnswersJobConsumer.JOB_TOPIC, DecisionJobConsumer.JOB_TOPIC,
        MainExtractionJobConsumer.JOB_TOPIC);

    private static final Logger LOGGER = LoggerFactory.getLogger(StopProcessingHandler.class);

    @Reference
    private ParseControl parses;

    @Reference
    private ParseService parseService;

    @Reference
    private ReadingRuns runs;

    @Reference
    private JobManager jobManager;

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource target = context.getTarget();
        final String path = target.getPath();
        SubmitterAccess.checkMayChange(SubmissionFiles.getSubmission(target), context.getActor());
        if (!ExtractionStatus.STATUS_RUNNING.equals(
            target.getValueMap().get(ExtractionStatus.STATUS_PROPERTY, String.class))) {
            // Nothing is going: a stop that arrives after the reading ended must not undo it
            LOGGER.info("Nothing to stop: submission={}", path);
            return;
        }
        // Read before the stop, since the thread forgets its files when it leaves the reading
        final Set<String> filesBeingRead = this.runs.getFiles(path);
        final boolean wasReadingThreadStopped = this.runs.stop(path);
        dropQueuedReadings(path);
        for (final File file : SubmissionFiles.getCurrentFiles(SubmissionFiles.getSubmission(target))) {
            // Only text the running step is reading. A claim with no reading thread is left over from a reading
            // that ended or from a restart, and has nothing to throw away
            final boolean consumed = wasReadingThreadStopped && filesBeingRead != null
                && filesBeingRead.contains(file.getPath());
            stopFile(context.getResourceResolver(), file, consumed);
        }
        ExtractionStatus.recordStatus(target, ExtractionStatus.STATUS_FAILED, ExtractionStatus.STOPPED_MESSAGE);
        ExtractionStatus.releaseClaim(target);
        LOGGER.info("Reading stopped: submission={} readingThreadStopped={}", path, wasReadingThreadStopped);
    }

    /**
     * Removes the reading jobs of this submission that have not started yet. A running one was told to stop
     * through {@code stop}; a queued one would start right after this returns and pay a model to read a document
     * the submitter just stopped.
     *
     * @param path the submission whose queued readings should not run
     */
    @SuppressWarnings("unchecked")
    private void dropQueuedReadings(final String path)
    {
        READING_TOPICS.stream()
            .map(topic -> this.jobManager.findJobs(JobManager.QueryType.QUEUED, topic, 0,
                Map.of(ReadingJobs.SUBMISSION_PROPERTY, path)))
            .filter(Objects::nonNull)
            .flatMap(Collection::stream)
            .forEach(job -> this.jobManager.removeJobById(job.getId()));
    }

    /**
     * Stops one file's part of the pipeline: abandons its queued parse, or deletes the Markdown the running step
     * was reading.
     *
     * @param resolver the session the submission is being written through
     * @param file the upload
     * @param consumed whether the running step was reading the file's parsed text
     * @throws PersistenceException if the file's record cannot be updated
     */
    private void stopFile(final ResourceResolver resolver, final File file, final boolean consumed)
        throws PersistenceException
    {
        final Resource resource = resolver.getResource(file.getPath());
        if (resource == null) {
            return;
        }
        final String status = file.getParseStatus();
        if (ParsePropertyNames.STATUS_QUEUED.equals(status)) {
            abandonParse(resource);
            markStopped(resource, "Stopped before the document was read");
            return;
        }
        if (consumed && ParsePropertyNames.STATUS_COMPLETED.equals(status)) {
            final Resource markdown = file.getMarkdownFile();
            if (markdown != null) {
                resolver.delete(markdown);
                markStopped(resource, "Stopped while the document was being read");
            }
        }
    }

    /**
     * Abandons a parse that has not finished.
     *
     * <p>The job record usually names the staging folder, and the sweep deletes the folder with the record. When
     * no record names it, the folder is deleted here, using the path the file kept: no sweep would ever find
     * it.</p>
     *
     * @param file the {@code sub:File} whose parse is still open
     */
    private void abandonParse(final Resource file)
    {
        final String jobId = file.getValueMap().get(ParseDocumentsHandler.PARSE_JOB_ID_PROPERTY, String.class);
        final String staged = file.getValueMap().get(ParseDocumentsHandler.SHARED_PATH_PROPERTY, String.class);
        // Delete only a folder no job record names. One a record names may be read in right now, or later if
        // this stop is undone
        final boolean isFolderOrphaned = this.parses.stopParse(jobId);
        if (isFolderOrphaned) {
            this.parseService.discardStaging(staged);
        }
    }

    /**
     * Marks this upload's parse as stopped, and removes the job id, staging path and token count recorded for it.
     *
     * @param file the {@code sub:File}
     * @param reason the message kept on the file
     * @throws PersistenceException if the file cannot be written
     */
    private static void markStopped(final Resource file, final String reason) throws PersistenceException
    {
        final ModifiableValueMap properties = file.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            throw new PersistenceException("Not allowed to record the stop on " + file.getPath());
        }
        properties.put(ParsePropertyNames.PARSE_STATUS_PROPERTY, ParsePropertyNames.STATUS_FAILED);
        properties.put(ParsePropertyNames.PARSE_ERROR_PROPERTY, reason);
        properties.remove(ParseDocumentsHandler.PARSE_JOB_ID_PROPERTY);
        properties.remove(ParseDocumentsHandler.SHARED_PATH_PROPERTY);
        properties.remove(ParsePropertyNames.TOKENS_PROPERTY);
    }
}
