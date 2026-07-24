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
package io.uhndata.iap.serialization.internal;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.function.Function;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.serialization.spi.ResourceJsonProcessor;

/**
 * Serialize {@code nt:file} children as a small download descriptor — the file's path (which, requested directly,
 * streams the file itself), name, content type, size and modification date — instead of descending into them, which
 * would dump the raw binary content into the JSON. Runs before the {@code deep} processor, which leaves the already
 * serialized value untouched. The name of this processor is {@code files}, and it is enabled by default; disable it
 * with the {@code -files} selector to get the raw node structure back.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class FilesProcessor implements ResourceJsonProcessor
{
    private static final Logger LOGGER = LoggerFactory.getLogger(FilesProcessor.class);

    private static final String CONTENT_CHILD = "jcr:content";

    @Override
    public String getName()
    {
        return "files";
    }

    @Override
    public int getPriority()
    {
        return 5;
    }

    @Override
    public boolean isEnabledByDefault(final Resource resource)
    {
        return true;
    }

    @Override
    public JsonValue processChild(final Node node, final Node child, final JsonValue input,
        final Function<Node, JsonValue> serializeNode)
    {
        // Another processor already serialized this child, leave its result in place
        if (input != null) {
            return input;
        }
        try {
            if (child.isNodeType("nt:file")) {
                return serializeFile(child);
            }
        } catch (RepositoryException e) {
            LOGGER.warn("Unexpected error while serializing file {}: {}", child, e.getMessage());
        }
        return input;
    }

    private JsonValue serializeFile(final Node file) throws RepositoryException
    {
        final JsonObjectBuilder result = Json.createObjectBuilder();
        result.add("jcr:primaryType", file.getPrimaryNodeType().getName());
        result.add("@path", file.getPath());
        result.add("@name", file.getName());
        if (file.hasNode(CONTENT_CHILD)) {
            final Node content = file.getNode(CONTENT_CHILD);
            if (content.hasProperty("jcr:mimeType")) {
                result.add("contentType", content.getProperty("jcr:mimeType").getString());
            }
            if (content.hasProperty("jcr:data")) {
                result.add("size", content.getProperty("jcr:data").getLength());
            }
            if (content.hasProperty("jcr:lastModified")) {
                result.add("lastModified", serializeDate(content.getProperty("jcr:lastModified").getDate()));
            }
        }
        return result.build();
    }

    private String serializeDate(final Calendar value)
    {
        // Use the ISO 8601 date+time format, same as the properties processor
        final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
        sdf.setTimeZone(value.getTimeZone());
        return sdf.format(value.getTime());
    }
}
