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
package io.uhndata.iap.schemas.editing.internal;

import java.util.Objects;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.sling.api.resource.Resource;

import io.uhndata.iap.schemas.models.Question;

/**
 * A utility class for the schema validity checks: finding the parts of a draft they inspect, and naming a part in a
 * problem.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class DraftParts
{
    /** The property holding a part's node type. */
    static final String PRIMARY_TYPE = "jcr:primaryType";

    /** A question's node type. */
    static final String QUESTION = "sch:Question";

    private DraftParts()
    {
        // Utility class
    }

    /**
     * The parts of a version of one node type, in the order they are presented.
     *
     * @param version the version
     * @param nodeType the node type, e.g. {@code sch:Question}
     * @return the parts, possibly none
     */
    static Stream<Resource> ofType(final Resource version, final String nodeType)
    {
        return descendants(version).filter(part -> nodeType.equals(part.getValueMap().get(PRIMARY_TYPE, "")));
    }

    /**
     * A question part as its model.
     *
     * @param part a part of type {@code sch:Question}
     * @return the model
     */
    static Question asQuestion(final Resource part)
    {
        return Objects.requireNonNull(part.adaptTo(Question.class),
            "A sch:Question resource failed to adapt to its model");
    }

    /**
     * How to call a part in a problem: by what the submitter reads, or by its path in the version when that is
     * blank.
     *
     * @param part the part
     * @param version the version it belongs to
     * @return a name to put in a sentence
     */
    static String describe(final Resource part, final Resource version)
    {
        return Stream.of("text", "label", "title")
            .map(property -> part.getValueMap().get(property, ""))
            .filter(name -> !name.isBlank())
            .findFirst()
            .map(name -> "\"" + name + "\"")
            .orElseGet(() -> part.getPath().substring(version.getPath().length() + 1));
    }

    /**
     * The part a condition belongs to: the nearest ancestor that is not part of the condition itself.
     *
     * @param conditionPart a node of a condition
     * @return the part the condition decides about
     */
    static Resource conditioned(final Resource conditionPart)
    {
        Resource current = conditionPart;
        Resource parent = current.getParent();
        while (parent != null && current.getValueMap().get(PRIMARY_TYPE, "").startsWith("cond:")) {
            current = parent;
            parent = current.getParent();
        }
        return current;
    }

    private static Stream<Resource> descendants(final Resource resource)
    {
        return StreamSupport.stream(resource.getChildren().spliterator(), false)
            .flatMap(child -> Stream.concat(Stream.of(child), descendants(child)));
    }
}
