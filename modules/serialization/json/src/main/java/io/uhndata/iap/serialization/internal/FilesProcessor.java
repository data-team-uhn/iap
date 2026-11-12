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
import io.uhndata.iap.utils.DateUtils;

/**
 * Keeps the content of a file out of the JSON. A {@code jcr:content} child of type {@code nt:resource} is written as
 * its path, so its binary {@code jcr:data} is never serialized. An {@code nt:file} gets its content's
 * {@code jcr:mimeType}, its {@code size} in bytes and its {@code jcr:lastModified} as its own keys. The name of this
 * processor is {@code files}, and it is enabled by default. With {@code -files}, {@code deep} serializes the binary
 * with the rest of the content node.
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
        if (input != null) {
            return input;
        }
        try {
            if (CONTENT_CHILD.equals(child.getName()) && child.isNodeType("nt:resource")) {
                return serializeFile(child);
            }
        } catch (RepositoryException e) {
            LOGGER.warn("Unexpected error while serializing file {}: {}", child, e.getMessage());
        }
        return input;
    }

    @Override
    public void leave(final Node node, final JsonObjectBuilder json, final Function<Node, JsonValue> serializeNode)
    {
        try {
            if (node.isNodeType("nt:file") && node.hasNode(CONTENT_CHILD)) {
                final Node content = node.getNode(CONTENT_CHILD);
                if (content.hasProperty("jcr:mimeType")) {
                    json.add("jcr:mimeType", content.getProperty("jcr:mimeType").getString());
                }
                if (content.hasProperty("jcr:data")) {
                    json.add("size", content.getProperty("jcr:data").getLength());
                }
                if (content.hasProperty("jcr:lastModified")) {
                    json.add("jcr:lastModified", DateUtils.toString(content.getProperty("jcr:lastModified").getDate()));
                }
            }
        } catch (RepositoryException ex) {
            LOGGER.warn("Failed to serialize file: {}", ex.getMessage());
        }
    }

    private JsonValue serializeFile(final Node file) throws RepositoryException
    {
        return Json.createValue(file.getPath());
    }
}
