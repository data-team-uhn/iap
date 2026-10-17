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
package io.uhndata.iap.submissions.models;

import java.util.Objects;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.entities.models.EntityPart;

/**
 * A Sling Model wrapping a {@code sub:Passage} node: one stretch of a document's Markdown, from where it starts to
 * where it ends. A {@link Section} consists of one or more.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Passage.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Passage extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code sub:Passage} node. */
    public static final String RESOURCE_TYPE = "sub/Passage";

    /** The name of the child node quoting where the passage starts. */
    private static final String START_CHILD = "start";

    /** The name of the child node quoting where the passage ends. */
    private static final String END_CHILD = "end";

    /**
     * Where the passage starts: its first words, usually a heading.
     *
     * @return the quoted start
     */
    @NotNull
    public Context getStart()
    {
        return Objects.requireNonNull(this.getChild(START_CHILD, Context.RESOURCE_TYPE, Context.class),
            "A passage always has a start");
    }

    /**
     * Where the passage ends: its last words, included in it.
     *
     * @return the quoted end
     */
    @NotNull
    public Context getEnd()
    {
        return Objects.requireNonNull(this.getChild(END_CHILD, Context.RESOURCE_TYPE, Context.class),
            "A passage always has an end");
    }
}
