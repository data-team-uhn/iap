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
import java.nio.file.Path;
import java.util.Map;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.utils.VersioningUtils;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.Payloads;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that reads a finished parse onto the file it was for, or records that the parse failed. It
 * runs in the same engine transaction as the rest of the {@code documentParsed} workflow, so a submission never
 * shows half a parse.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class IngestParseHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "ingestParse";

    @Reference
    private ParseResultIngester ingester;

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        VersioningUtils.checkOut(context.getTarget());
        final WorkflowEvent event = context.getEvent();
        final Resource file = getFile(context, Payloads.requireText(event, ParseCompletionHandler.FILE_KEY,
            "The event does not say which file was parsed"));
        if (Boolean.TRUE.equals(event.get(ParseCompletionHandler.SUCCEEDED_KEY))) {
            ingest(file, event);
        } else {
            final String reported = Payloads.text(event, ParseCompletionHandler.ERROR_KEY);
            ParseResultIngester.writeParseProperties(file, Map.of(
                ParsePropertyNames.PARSE_STATUS_PROPERTY, ParsePropertyNames.STATUS_FAILED,
                ParsePropertyNames.PARSE_ERROR_PROPERTY,
                reported == null ? "The parse failed without details" : reported));
        }
    }

    private void ingest(final Resource file, final WorkflowEvent event) throws WorkflowException, PersistenceException
    {
        final String markdown = Payloads.requireText(event, ParseCompletionHandler.MARKDOWN_KEY,
            "A successful parse names the Markdown it produced");
        try {
            this.ingester.ingest(file, Path.of(markdown), readPath(event, ParseCompletionHandler.PDF_KEY),
                readTokens(event));
        } catch (final IOException e) {
            throw new PersistenceException("Could not read what the parse produced: " + e.getMessage(), e);
        }
    }

    /**
     * Returns the size of the document in tokens, as the daemon measured it.
     *
     * @param event the parse outcome
     * @return the token count, or {@code null} when the event has none
     */
    private static Long readTokens(final WorkflowEvent event)
    {
        final Object value = event.get(ParseCompletionHandler.TOKENS_KEY);
        return value instanceof Number ? ((Number) value).longValue() : null;
    }

    private static Resource getFile(final WorkflowTaskContext context, final String path) throws InvalidPayloadException
    {
        final Resource file = context.getResourceResolver().getResource(path);
        if (file == null || !file.isResourceType(File.RESOURCE_TYPE)) {
            throw new InvalidPayloadException("There is no uploaded file at " + path);
        }
        return file;
    }

    private static Path readPath(final WorkflowEvent event, final String key)
    {
        final String value = Payloads.text(event, key);
        return value == null ? null : Path.of(value);
    }
}
