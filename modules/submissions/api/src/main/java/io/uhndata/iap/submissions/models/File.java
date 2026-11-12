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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.utils.Referenceable;

/**
 * A Sling Model wrapping a {@code sub:File} node: one uploaded file, and the renditions parsing made of it, such as
 * the Markdown.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = File.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class File extends EntityPart implements Referenceable
{
    /** The {@code sling:resourceType} of a {@code sub:File} node. */
    public static final String RESOURCE_TYPE = "sub/File";

    /** The name of the child node holding the upload as it was received. */
    private static final String UPLOADED_FILE_CHILD = "uploadedFile";

    private static final String FILE_RESOURCE_TYPE = "nt:file";

    @ValueMapValue
    private String parseStatus;

    @ValueMapValue
    private String parseError;

    @ValueMapValue
    private Long tokens;

    /**
     * How far parsing got.
     *
     * @return one of {@code queued}, {@code active}, {@code completed} and {@code failed}; {@code null} if none is
     *         recorded
     */
    @Nullable
    public String getParseStatus()
    {
        return this.parseStatus;
    }

    /**
     * Why parsing failed.
     *
     * @return the error message; {@code null} if none is recorded
     */
    @Nullable
    public String getParseError()
    {
        return this.parseError;
    }

    /**
     * The token count of the Markdown rendition, estimated as its length in characters divided by 4.
     *
     * @return the token count; {@code null} if none is recorded
     */
    @Nullable
    public Long getTokens()
    {
        return this.tokens;
    }

    /**
     * The upload, exactly as it was received.
     *
     * @return the {@code nt:file} resource; {@code null} if the upload is not stored yet
     */
    @Nullable
    public Resource getUploadedFile()
    {
        return this.resource.getChild(UPLOADED_FILE_CHILD);
    }

    /**
     * The renditions parsing produced, such as the Markdown. The upload itself is not one of them.
     *
     * @return the {@code nt:file} resources; empty if nothing has been rendered yet
     */
    @NotNull
    public List<Resource> getRenditions()
    {
        final List<Resource> result = new ArrayList<>();
        for (final Resource child : this.resource.getChildren()) {
            if (child.isResourceType(FILE_RESOURCE_TYPE) && !UPLOADED_FILE_CHILD.equals(child.getName())) {
                result.add(child);
            }
        }
        return result;
    }

    @Override
    @NotNull
    public String getIdentifier()
    {
        return this.identifier;
    }
}
