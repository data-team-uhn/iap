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

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.entities.models.EntityPart;

/**
 * A Sling Model wrapping a {@code sub:Evidence} node: one passage backing the extracted answer of an
 * {@link Answer}, kept as a node rather than a plain string so the quote stays linked to the {@link Chunk} it was
 * taken from.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Evidence.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Evidence extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code sub:Evidence} node. */
    public static final String RESOURCE_TYPE = "sub/Evidence";

    @ValueMapValue
    private String chunk;

    @ValueMapValue
    private String quote;

    @ValueMapValue
    private Long page;

    /**
     * The chunk this passage was taken from.
     *
     * @return a chunk, or {@code null} if not set or unresolvable
     */
    @Nullable
    public Chunk getChunk()
    {
        return this.getReference(this.chunk, Chunk.class);
    }

    /**
     * The quoted text.
     *
     * @return the quote, or {@code null} if not set
     */
    @Nullable
    public String getQuote()
    {
        return this.quote;
    }

    /**
     * The page of the source PDF this passage was quoted from, when known. Absent for documents that carry no page
     * markers, e.g. anything that came in as DOCX.
     *
     * @return a page number, or {@code null} if unknown
     */
    @Nullable
    public Long getPage()
    {
        return this.page;
    }
}
