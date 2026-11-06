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
 * A Sling Model wrapping a {@code sub:Context} node: a quote, and where it comes from, a passage of a document or an
 * answer. It backs an {@link Extraction} or a {@link Comment}, or marks where a {@link Passage} starts or ends.
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
    private String source;

    @ValueMapValue
    private String quote;

    @ValueMapValue
    private String prefix;

    @ValueMapValue
    private String suffix;

    @ValueMapValue
    private String header;

    @ValueMapValue
    private Long page;

    /**
     * The document revision or the answer the quote comes from. The link is weak, so one removed since is skipped.
     *
     * @return the source; {@code null} if none is recorded, it has been removed, or this session cannot read it
     */
    @Nullable
    public EntityPart getSource()
    {
        return this.getReference(this.source, EntityPart.class);
    }

    /**
     * The quoted text.
     *
     * @return the quote; {@code null} if none is set
     */
    @Nullable
    public String getQuote()
    {
        return this.quote;
    }

    /**
     * A little of the text just before the quote, so the passage can still be found when the same words occur more
     * than once.
     *
     * @return the text before the quote; {@code null} if none is recorded
     */
    @Nullable
    public String getPrefix()
    {
        return this.prefix;
    }

    /**
     * A little of the text just after the quote, as the {@link #getPrefix() prefix} holds the text just before it.
     *
     * @return the text after the quote; {@code null} if none is recorded
     */
    @Nullable
    public String getSuffix()
    {
        return this.suffix;
    }

    /**
     * The nearest heading above the quoted text, which names the section it came from.
     *
     * @return the heading; {@code null} if none was found
     */
    @Nullable
    public String getHeader()
    {
        return this.header;
    }

    /**
     * The page of the source PDF the quote was taken from.
     *
     * @return the page number; {@code null} if it is unknown, as for a document with no page markers, such as one
     *         uploaded as DOCX
     */
    @Nullable
    public Long getPage()
    {
        return this.page;
    }
}
