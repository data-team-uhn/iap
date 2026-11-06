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

import java.util.Set;

import javax.jcr.Node;
import javax.jcr.Property;

import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.serialization.spi.ResourceJsonProcessor;

/**
 * Leaves out the properties that say how content is stored rather than what it holds. The name of this processor is
 * {@code simple}.
 *
 * <p>
 * It drops every {@code sling:} property, {@code sling:resourceType} included. Of the {@code jcr:} properties, it
 * keeps {@code jcr:primaryType}, {@code jcr:uuid}, and the four that say who created and last changed the content,
 * and when. It drops the rest, such as {@code jcr:mixinTypes} and the {@code jcr:baseVersion} that every
 * {@code mix:versionable} node carries.
 * </p>
 *
 * <p>
 * A module can register its own {@code simple} processor at a higher priority, for a node type whose bulk is in its
 * children or behind a reference.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class SimpleProcessor implements ResourceJsonProcessor
{
    /** The {@code jcr:} properties kept. */
    private static final Set<String> CONTENT_JCR_PROPERTIES = Set.of(
        "jcr:primaryType", "jcr:uuid", "jcr:created", "jcr:createdBy", "jcr:lastModified", "jcr:lastModifiedBy");

    @Override
    public String getName()
    {
        return "simple";
    }

    @Override
    public int getPriority()
    {
        return 25;
    }

    @Override
    public String processPropertyName(final Node node, final Property property, final String input)
    {
        if (input != null && isRepositoryBookkeeping(input)) {
            return null;
        }
        return input;
    }

    private boolean isRepositoryBookkeeping(final String name)
    {
        return name.startsWith("sling:")
            || (name.startsWith("jcr:") && !CONTENT_JCR_PROPERTIES.contains(name));
    }
}
