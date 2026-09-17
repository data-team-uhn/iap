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

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.documents.api.ParseService;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that sends a submission's uploads to be parsed: every latest upload not parsed yet is staged
 * on the shared volume and queued, and the submission is marked as being read. A process puts this step right
 * after the uploading is done; the answers arrive later, through the {@code documentParsed} and
 * {@code extractAnswers} system workflows.
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
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource target = context.getTarget();
        boolean queued = false;
        for (final File file : SubmissionFiles.currentFiles(SubmissionFiles.submission(target))) {
            if (file.getParseStatus() != null) {
                // Already sent, or already read: a parse is asked for once per upload
                continue;
            }
            final Resource resource = context.getResourceResolver().getResource(file.getPath());
            if (resource != null && queue(resource, file)) {
                queued = true;
            }
        }
        if (queued) {
            ExtractionStatus.record(target, ExtractionStatus.RUNNING, null);
        }
    }

    /**
     * Stage one upload and queue its parse, recording on the file where it went.
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
        try (InputStream in = bytes) {
            final String staged = this.parseService.stage(properties.get(FILE_NAME, String.class), in);
            final String jobId = this.parseService.queue(staged, true, file.getPath());
            properties.put(ParsePropertyNames.PARSE_STATUS, ParsePropertyNames.STATUS_QUEUED);
            properties.put(PARSE_JOB_ID, jobId);
            properties.put(SHARED_PATH, staged);
        } catch (final IOException e) {
            throw new PersistenceException("Could not queue the parse of " + file.getPath() + ": " + e.getMessage(),
                e);
        }
        return true;
    }
}
