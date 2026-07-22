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
package io.uhndata.iap.tags.spi;

import java.util.Set;

/**
 * A read-only snapshot of the tag definitions stored under {@code /Tags}, as seen by the commit being processed.
 * Unlike the {@code TagDefinition} Sling Model, this view works at the Oak level, without needing a resource
 * resolver, so it is usable inside commit hooks processing any session's commits.
 *
 * @version $Id$
 * @since 0.1.0
 */
public interface TagDefinitions
{
    /**
     * The names of all the defined tags.
     *
     * @return the tag names, an empty set if there are no definitions
     */
    Set<String> getNames();

    /**
     * Checks whether a tag is defined.
     *
     * @param name a tag name
     * @return {@code true} if a definition with this name exists under {@code /Tags}
     */
    boolean isDefined(String name);

    /**
     * Checks whether a tag is aggregated: a node implicitly carries it when any of its descendants explicitly does.
     *
     * @param name a tag name
     * @return {@code true} if the tag is defined and marked as aggregated
     */
    boolean isAggregated(String name);

    /**
     * Checks whether a tag is inheritable: resources under a tagged node implicitly carry it too.
     *
     * @param name a tag name
     * @return {@code true} if the tag is defined and marked as inheritable
     */
    boolean isInheritable(String name);
}
