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

import java.util.ArrayList;
import java.util.List;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;

import io.uhndata.iap.entities.models.EntityPart;

/**
 * A Sling Model wrapping a {@code sub:Document} node: a document attached to the submission, e.g. the signed
 * patient consent.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Document.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Document extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code sub:Document} node. */
    public static final String RESOURCE_TYPE = "sub/Document";

    private static final String FILE_RESOURCE_TYPE = "nt:file";

    @ValueMapValue
    private String title;

    @ValueMapValue
    private String description;

    @ValueMapValue
    private String fulfills;

    /**
     * The human-readable name of this document.
     *
     * @return a title, or {@code null} if not set
     */
    public String getTitle()
    {
        return this.title;
    }

    /**
     * An optional description of what this document contains.
     *
     * @return a description, or {@code null} if not set
     */
    public String getDescription()
    {
        return this.description;
    }

    /**
     * The identifier of the {@code sch:Requirement} (typically a {@code sch:DocumentRequirement}) this document
     * fulfills.
     *
     * @return an UUID, or {@code null} if not set
     */
    public String getFulfills()
    {
        return this.fulfills;
    }

    /**
     * The uploaded attachment(s), in the order they were attached.
     *
     * @return a list of file resources, empty if none were attached yet
     */
    public List<Resource> getAttachments()
    {
        final List<Resource> result = new ArrayList<>();
        for (final Resource child : this.resource.getChildren()) {
            if (child.isResourceType(FILE_RESOURCE_TYPE)) {
                result.add(child);
            }
        }
        return result;
    }
}
