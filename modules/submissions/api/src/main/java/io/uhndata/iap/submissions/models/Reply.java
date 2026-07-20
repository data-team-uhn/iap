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

import io.uhndata.iap.entities.models.EntityPart;

/**
 * A Sling Model wrapping a {@code sub:Reply} node: a single message in the discussion thread attached to a
 * {@link ReviewComment}, e.g. the submitter responding to a reviewer's question, or the reviewer following up.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Reply.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Reply extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code sub:Reply} node. */
    public static final String RESOURCE_TYPE = "sub/Reply";

    @ValueMapValue
    private String text;

    @ValueMapValue
    private String author;

    /**
     * The reply text.
     *
     * @return the reply text
     */
    public String getText()
    {
        return this.text;
    }

    /**
     * Identifies who wrote this reply (the reviewer or the submitter). Not necessarily the same as
     * {@code jcr:createdBy}: see {@link ReviewComment#getAuthor()}.
     *
     * @return a principal name, or an external identifier
     */
    public String getAuthor()
    {
        return this.author;
    }
}
