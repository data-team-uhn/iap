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
import java.util.Objects;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.documents.api.ParseService;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that sends a submission's uploads to be parsed: every latest upload still wanting one is
 * staged on the shared volume and queued, and the submission is marked as being read. A process puts this step
 * right after the uploading is done; the answers arrive later, through the {@code documentParsed} and
 * {@code extractAnswers} system workflows.
 *
 * <p>Also the whole of the {@code retryParse} system workflow, which is how a submitter asks again after a parse
 * failed. Nothing there is retry-specific: running this a second time sends exactly what still wants sending.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class ParseDocumentsHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String NAME = "parseDocuments";

    /** Where a file keeps the name it arrived under. */
    static final String FILE_NAME = "fileName";

    /** Where a file records the parse job it was queued under. */
    static final String PARSE_JOB_ID = "parseJobId";

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
        return NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws PersistenceException
    {
        final Resource target = context.getTarget();
        int queued = 0;
        for (final File file : SubmissionFiles.currentFiles(SubmissionFiles.submission(target))) {
            if (!isParseWanted(file)) {
                continue;
            }
            final Resource resource = context.getResourceResolver().getResource(file.getPath());
            if (resource != null && queue(resource, file)) {
                queued++;
            }
        }
        if (queued > 0) {
            ExtractionStatus.record(target, ExtractionStatus.RUNNING, null);
            // A new document is a new reading to be had, so whoever took the last one no longer holds it
            releaseReading(target);
            LOGGER.info("Reading started: submission={} parsesQueued={}", target.getPath(), queued);
        }
    }

    /**
     * Whether this upload is one to send: never sent, or sent and failed.
     *
     * <p>A parse is asked for once per upload, so one already queued or already read is left alone. A failed one
     * is not, which is what makes asking again worth offering: what fails is usually the daemon being unreachable
     * rather than the document, and the upload is still sitting there perfectly readable.</p>
     *
     * @param file the upload
     * @return {@code true} when it should be staged and queued
     */
    private static boolean isParseWanted(final File file)
    {
        final String status = file.getParseStatus();
        return status == null || ParsePropertyNames.STATUS_FAILED.equals(status);
    }

    /**
     * Take down the claim a previous reading left, so the jobs these parses queue can take it.
     *
     * @param target the submission, which recording the status has just shown can be written
     */
    private static void releaseReading(final Resource target)
    {
        Objects.requireNonNull(target.adaptTo(ModifiableValueMap.class),
            "Recording the status has already shown the submission can be written")
            .remove(ExtractionStatus.READING_CLAIMED);
    }

    /**
     * Stage one upload and queue its parse, recording on the file where it went.
     *
     * <p>This step is the one place in the reading that reaches outside the engine's transaction: staging writes
     * to the shared volume and queueing commits a job node through a session of its own, both before the walk
     * that asked for them has committed. It is that way round on purpose - the job node has to be visible to the
     * consumer before the Sling job is queued - and it is why a failure here discards what it staged rather than
     * leaving a folder on the volume that no job node names and no sweep can find.</p>
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
            return false;
        }
        final ModifiableValueMap properties = resource.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            throw new PersistenceException("Not allowed to record the parse on " + file.getPath());
        }
        String staged = null;
        try (InputStream in = bytes) {
            staged = this.parseService.stage(properties.get(FILE_NAME, String.class), in);
            final String jobId = this.parseService.queue(staged, file.getPath());
            properties.put(ParsePropertyNames.PARSE_STATUS, ParsePropertyNames.STATUS_QUEUED);
            // Whatever went wrong last time is being tried again, so it is no longer what is wrong with this file
            properties.remove(ParsePropertyNames.PARSE_ERROR);
            properties.put(PARSE_JOB_ID, jobId);
            properties.put(SHARED_PATH, staged);
            LOGGER.info("Document sent to be parsed: file={} job={} name={}", file.getPath(), jobId,
                properties.get(FILE_NAME, String.class));
        } catch (final IOException e) {
            // A copy staged for a parse that was never queued is a folder nothing names: no job node records it,
            // so no sweep will ever come back for it. It goes now or it stays for good.
            this.parseService.discardStaging(staged);
            throw new PersistenceException("Could not queue the parse of " + file.getPath() + ": " + e.getMessage(),
                e);
        }
        return true;
    }
}
