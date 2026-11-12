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

import org.jetbrains.annotations.NotNull;

/**
 * The tag definitions under {@code /Tags}, as the commit being processed leaves them. It reads Oak node states and
 * needs no resource resolver, so a commit editor can use it in any session's commit.
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
    @NotNull
    Set<String> getNames();

    /**
     * Checks whether a tag is defined.
     *
     * @param name a tag name
     * @return {@code true} if a definition with this name exists under {@code /Tags}
     */
    boolean isDefined(@NotNull String name);

    /**
     * Checks whether a tag is aggregated: a node carries it when content below it does.
     *
     * @param name a tag name
     * @return {@code true} if the tag is defined and marked as aggregated
     */
    boolean isAggregated(@NotNull String name);

    /**
     * Checks whether a tag is inheritable: the content under a tagged node carries it too.
     *
     * @param name a tag name
     * @return {@code true} if the tag is defined and marked as inheritable
     */
    boolean isInheritable(@NotNull String name);
}
