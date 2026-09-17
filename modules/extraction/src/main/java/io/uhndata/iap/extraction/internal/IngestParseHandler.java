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

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that reads a finished parse onto the file it was for, or records that the parse failed. What
 * the parse produced lands in the engine's transaction along with everything else the {@code documentParsed}
 * workflow does, so a submission never shows half a parse.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class IngestParseHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String NAME = "ingestParse";

    @Reference
    private ParseResultIngester ingester;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final WorkflowEvent event = context.getEvent();
        final Resource file = file(context, string(event, ParseCompletionHandler.FILE));
        if (Boolean.TRUE.equals(event.get(ParseCompletionHandler.SUCCEEDED))) {
            ingest(file, event);
        } else {
            final ModifiableValueMap properties = properties(file);
            properties.put(ParsePropertyNames.PARSE_STATUS, ParsePropertyNames.STATUS_FAILED);
            properties.put(ParsePropertyNames.PARSE_ERROR, string(event, ParseCompletionHandler.ERROR) == null
                ? "The parse failed without details" : string(event, ParseCompletionHandler.ERROR));
        }
    }

    private void ingest(final Resource file, final WorkflowEvent event) throws WorkflowException, PersistenceException
    {
        final String markdown = string(event, ParseCompletionHandler.MARKDOWN);
        if (markdown == null) {
            throw new InvalidPayloadException("A successful parse names the Markdown it produced");
        }
        try {
            this.ingester.ingest(file, Path.of(markdown), path(event, ParseCompletionHandler.PDF),
                path(event, ParseCompletionHandler.CHUNKS));
        } catch (final IOException e) {
            throw new PersistenceException("Could not read what the parse produced: " + e.getMessage(), e);
        }
    }

    private static Resource file(final WorkflowTaskContext context, final String path) throws InvalidPayloadException
    {
        if (path == null) {
            throw new InvalidPayloadException("The event does not say which file was parsed");
        }
        final Resource file = context.getResourceResolver().getResource(path);
        if (file == null || !file.isResourceType(File.RESOURCE_TYPE)) {
            throw new InvalidPayloadException("There is no uploaded file at " + path);
        }
        return file;
    }

    private static ModifiableValueMap properties(final Resource file) throws PersistenceException
    {
        final ModifiableValueMap properties = file.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            throw new PersistenceException("Not allowed to record the parse on " + file.getPath());
        }
        return properties;
    }

    private static String string(final WorkflowEvent event, final String key)
    {
        final Object value = event.get(key);
        return value instanceof String ? (String) value : null;
    }

    private static Path path(final WorkflowEvent event, final String key)
    {
        final String value = string(event, key);
        return value == null ? null : Path.of(value);
    }
}
