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
 * A Sling Model wrapping a {@code sub:Context} node: the context a quote comes from, backing an {@link Extraction}
 * or a {@link Comment}: a passage of a document, or an answer. Kept as a node rather than a plain string, so the quote
 * stays linked to the page and section it was taken from.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Context.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Context extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code sub:Context} node. */
    public static final String RESOURCE_TYPE = "sub/Context";

    @ValueMapValue
    private String quote;

    @ValueMapValue
    private String header;

    @ValueMapValue
    private Long page;

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
     * The nearest header above the quoted text, so a citation can name the section it came from.
     *
     * @return a header, or {@code null} if none was detected
     */
    @Nullable
    public String getHeader()
    {
        return this.header;
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
