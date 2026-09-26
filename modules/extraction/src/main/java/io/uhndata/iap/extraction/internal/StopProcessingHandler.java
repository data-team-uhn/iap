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
import java.util.Map;
import java.util.Objects;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.JobManager;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.documents.api.ParseControl;
import io.uhndata.iap.documents.api.ParseService;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that stops a reading still in progress. A parse that has not come back is abandoned: the daemon
 * is told, the staging folder is wiped, and the job record is deleted. A parse already read in stops the model
 * call, and the Markdown that call was consuming is removed so the next try parses again rather than reading a
 * document the submitter just threw out.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class StopProcessingHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String NAME = "stopProcessing";

    private static final Logger LOGGER = LoggerFactory.getLogger(StopProcessingHandler.class);

    @Reference
    private ParseControl parses;

    @Reference
    private ParseService parseService;

    @Reference
    private ReadingRuns runs;

    @Reference
    private JobManager jobManager;

    @Reference
    private ParsedDocuments documents;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws PersistenceException
    {
        final Resource target = context.getTarget();
        final String path = target.getPath();
        final boolean reading = this.runs.stop(path);
        final boolean claimed = Boolean.TRUE.equals(
            target.getValueMap().get(ExtractionStatus.READING_CLAIMED, Boolean.class));
        dropQueuedReadings(path);
        for (final File file : SubmissionFiles.currentFiles(SubmissionFiles.submission(target))) {
            stopFile(context.getResourceResolver(), file, reading || claimed);
        }
        ExtractionStatus.record(target, ExtractionStatus.FAILED, ExtractionStatus.STOPPED);
        releaseClaim(target);
        this.documents.forget();
        LOGGER.info("Reading stopped: submission={} modelCall={}", path, reading);
    }

    /**
     * Drop the readings still waiting to start. One already inside the model is the thread {@code stop} interrupted;
     * one still on the queue would start the moment this returns and pay for a document the submitter just cancelled.
     *
     * @param path the submission whose queued readings should not run
     */
    @SuppressWarnings("unchecked")
    private void dropQueuedReadings(final String path)
    {
        final Collection<Job> waiting = this.jobManager.findJobs(JobManager.QueryType.QUEUED,
            ExtractAnswersJobConsumer.TOPIC, 0, Map.of(ExtractAnswersJobConsumer.SUBMISSION, path));
        if (waiting == null) {
            return;
        }
        for (final Job job : waiting) {
            this.jobManager.removeJobById(job.getId());
        }
    }

    /**
     * Stop one file's part of the pipeline.
     *
     * @param resolver the session the submission is being written through
     * @param file the upload
     * @param consumed whether a reading had already taken the parsed text
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
            final Resource markdown = file.getFileMarkdown();
            if (markdown != null) {
                resolver.delete(markdown);
                markStopped(resource, "Stopped while the document was being read");
            }
        }
    }

    /**
     * Drop a parse that has not finished, including the folder it was working in.
     *
     * <p>The job record normally names that folder. When the record is already gone the file still remembers where
     * the upload was staged, and that copy is wiped too: a folder nothing names is one no sweep will ever find.</p>
     *
     * @param file the {@code sub:File} whose parse is still open
     */
    private void abandonParse(final Resource file)
    {
        final String jobId = file.getValueMap().get(ParseDocumentsHandler.PARSE_JOB_ID, String.class);
        final String staged = file.getValueMap().get(ParseDocumentsHandler.SHARED_PATH, String.class);
        if (jobId != null) {
            this.parses.abandon(jobId);
        }
        this.parseService.discardStaging(staged);
    }

    /**
     * Record that this upload's parse was stopped, and forget the job it was queued under.
     *
     * @param file the {@code sub:File}
     * @param reason what to keep on the file
     * @throws PersistenceException if the file cannot be written
     */
    private static void markStopped(final Resource file, final String reason) throws PersistenceException
    {
        final ModifiableValueMap properties = file.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            throw new PersistenceException("Not allowed to record the stop on " + file.getPath());
        }
        properties.put(ParsePropertyNames.PARSE_STATUS, ParsePropertyNames.STATUS_FAILED);
        properties.put(ParsePropertyNames.PARSE_ERROR, reason);
        properties.remove(ParseDocumentsHandler.PARSE_JOB_ID);
        properties.remove(ParseDocumentsHandler.SHARED_PATH);
        properties.remove(ParsePropertyNames.TOKENS);
    }

    /**
     * Let the next reading take the claim this one held.
     *
     * @param target the submission, which recording the status has just shown can be written
     */
    private static void releaseClaim(final Resource target)
    {
        Objects.requireNonNull(target.adaptTo(ModifiableValueMap.class),
            "Recording the status has already shown the submission can be written")
            .remove(ExtractionStatus.READING_CLAIMED);
    }
}
