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
 * Serialize only what a consumer of the content is likely to have asked for, leaving out the properties that describe
 * how the content is stored rather than what it holds. The name of this processor is {@code simple}.
 *
 * <p>
 * Dropped are all {@code sling:} properties, which say which scripts render the resource, and all {@code jcr:}
 * properties except the few that identify content rather than administer it: its type, its identifier, and who
 * created and last changed it. On a versionable type the rest is a large share of every node, the version history,
 * base version, predecessors and checked-out flag are repeated on each one, so a tree of entities shrinks
 * considerably without losing anything a reader of the content would recognise.
 * </p>
 *
 * <p>
 * This is the general case. A node type whose bulk is elsewhere (in its children, or behind a reference) is
 * summarized by a processor of its own registered under this same name, which runs afterwards; see the
 * {@code ResourceJsonProcessor} contract for how processors of a name compose.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class SimpleProcessor implements ResourceJsonProcessor
{
    /**
     * The {@code jcr:} properties that describe the content itself, rather than the repository's bookkeeping for it.
     */
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
