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
import java.io.InputStream;
import java.util.Optional;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.documents.api.ParseService;
import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.submissions.models.Document;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that sends a submission's uploads to be parsed: every latest upload still wanting one is
 * staged on the shared volume and queued, and the submission is marked as being read. It is meant to follow the
 * upload; today only the {@code retryParse} workflow runs it. The answers arrive later, through the
 * {@code documentParsed} and {@code extractAnswers} system workflows.
 *
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class ParseDocumentsHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "parseDocuments";

    /** Where a file may keep the name it arrived under; the upload handler puts it on the document's title instead. */
    static final String FILE_NAME = "fileName";

    /** Where a file records the parse job it was queued under. */
    static final String PARSE_JOB_ID = "parseJobId";

    /** Where a schema version names the workflow that reads its documents, absent when none reads them. */
    static final String READING_WORKFLOW = "readingWorkflow";

    /** Where a file records where its upload was staged for the daemon. */
    static final String SHARED_PATH = "sharedPath";

    private static final Logger LOGGER = LoggerFactory.getLogger(ParseDocumentsHandler.class);

    private static final String JCR_CONTENT = "jcr:content";

    private static final String JCR_DATA = "jcr:data";

    @Reference
    private ParseService parseService;

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource target = context.getTarget();
        final Submission submission = SubmissionFiles.getSubmission(target);
        SubmitterAccess.checkMayChange(submission, context.getActor());
        // A schema with no reading workflow has nothing to parse for, so its uploads are left alone
        if (submission.getSchemaVersion().get(READING_WORKFLOW, String.class) == null) {
            return;
        }
        int queued = 0;
        for (final File file : SubmissionFiles.getFilesToParse(submission)) {
            // Skip files that are already parsed
            final String status = file.getParseStatus();
            if (status != null && !ParsePropertyNames.STATUS_FAILED.equals(status)) {
                continue;
            }
            // Queue the file for parsing.
            final Resource resource = context.getResourceResolver().getResource(file.getPath());
            if (resource != null && queue(resource, file)) {
                // Increment the number of queued files.
                queued++;
            }
        }
        if (queued > 0) {
            ExtractionStatus.record(target, ExtractionStatus.STATUS_RUNNING, null);
            // A new document is a new reading to be had, so whoever took the last one no longer holds it
            ExtractionStatus.releaseClaim(target);
            LOGGER.info("Reading started: submission={} parsesQueued={}", target.getPath(), queued);
        }
    }

    /**
     * Stage one upload and queue its parse, recording on the file where it went.
     *
     * <p>This step is the one place in the reading that reaches outside the engine's transaction: staging writes
     * to the shared volume and queueing commits a job node through a session of its own, both before the walk
     * that asked for them has committed. It is that way round on purpose - the job node has to be visible to the
     * consumer before the Sling job is queued - and it is why a failure to queue discards what it staged rather
     * than leave the folder to a sweep that may never find it.</p>
     *
     * @return {@code false} when the file holds no upload to parse
     */
    private boolean queue(final Resource resource, final File file) throws PersistenceException
    {
        final Resource uploaded = file.getUploadedFile();
        final Resource content = uploaded == null ? null : uploaded.getChild(JCR_CONTENT);
        final InputStream bytes = content == null ? null : content.getValueMap().get(JCR_DATA, InputStream.class);
        if (bytes == null) {
            LOGGER.warn("{} holds no upload to parse", file.getPath());
            // An upload with no content is damaged data, and it is silently left unread
            ErrorLogger.logProblem("An uploaded file holds no content to parse",
                ErrorContext.of(ParseDocumentsHandler.class, "queue").about(file.getPath()));
            return false;
        }
        final ModifiableValueMap properties = resource.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            throw new PersistenceException("Not allowed to record the parse on " + file.getPath());
        }
        final String name = getUploadName(file);
        String staged = null;
        try (InputStream in = bytes) {
            staged = this.parseService.stage(name, in);
            final String jobId = this.parseService.queue(staged, file.getPath());
            properties.put(ParsePropertyNames.PARSE_STATUS, ParsePropertyNames.STATUS_QUEUED);
            // Whatever went wrong last time is being tried again, so it is no longer what is wrong with this file
            properties.remove(ParsePropertyNames.PARSE_ERROR);
            properties.put(PARSE_JOB_ID, jobId);
            properties.put(SHARED_PATH, staged);
            LOGGER.info("Document sent to be parsed: file={} job={} name={}", file.getPath(), jobId, name);
        } catch (final IOException e) {
            // A copy staged for a parse that was never queued usually has no job node naming it, so no sweep would
            // come back for it. It goes now. A stage that failed partway names nothing to delete, and stays.
            this.parseService.discardStaging(staged);
            throw new PersistenceException("Could not queue the parse of " + file.getPath() + ": " + e.getMessage(),
                e);
        }
        return true;
    }

    /**
     * The name to stage an upload under. Its extension is what tells the daemon what kind of document it is.
     *
     * @param file the upload
     * @return the file's own {@code fileName}, else the title of its document, or {@code null} when neither is set
     */
    private static String getUploadName(final File file)
    {
        final String own = file.get(FILE_NAME, String.class);
        if (own != null) {
            return own;
        }
        // The upload handler keeps the uploaded name as the title of the document: file, then version, then document
        return Optional.ofNullable(file.getParent()).map(Content::getParent)
            .map(document -> document.as(Document.class)).map(Document::getTitle).orElse(null);
    }
}
