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

import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.entities.models.EntityPart;

/**
 * A Sling Model wrapping a {@code sub:ToC} node: the outline of one {@link File} — where it was read from, the
 * records it holds, and the lines the printed table of contents occupies.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = ToC.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class ToC extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code sub:ToC} node. */
    public static final String RESOURCE_TYPE = "sub/ToC";

    @ValueMapValue
    private String source;

    @ValueMapValue
    private String[] titles;

    @ValueMapValue
    private Long startLine;

    @ValueMapValue
    private Long endLine;

    /**
     * Where the outline came from: the PDF's bookmarks, or the Markdown's own table of contents.
     *
     * @return a source, or {@code null} if not recorded
     */
    @Nullable
    public String getSource()
    {
        return this.source;
    }

    /**
     * The titles of the outline records, in document order.
     *
     * @return a list of titles, empty if the outline holds no records
     */
    @NotNull
    public List<String> getTitles()
    {
        return this.titles == null ? List.of() : List.of(this.titles);
    }

    /**
     * The first line of the printed table of contents within the Markdown, so that it can be skipped when reading
     * the body.
     *
     * @return a line number, or {@code null} if no printed table of contents was found
     */
    @Nullable
    public Long getStartLine()
    {
        return this.startLine;
    }

    /**
     * The last line of the printed table of contents within the Markdown.
     *
     * @return a line number, or {@code null} if no printed table of contents was found
     */
    @Nullable
    public Long getEndLine()
    {
        return this.endLine;
    }
}
