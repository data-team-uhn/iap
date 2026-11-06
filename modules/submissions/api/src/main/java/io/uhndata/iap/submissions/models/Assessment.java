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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.schemas.models.Topic;
import io.uhndata.iap.utils.Referenceable;

/**
 * A Sling Model wrapping a {@code sub:Assessment} node: one assessment of one {@link Topic} of the schema, in a
 * reviewer's {@link Review} or in the AI's {@link Screening}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Assessment.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Assessment extends EntityPart implements Referenceable
{
    /** The {@code sling:resourceType} of a {@code sub:Assessment} node. */
    public static final String RESOURCE_TYPE = "sub/Assessment";

    @ValueMapValue
    private String topic;

    @ValueMapValue
    private String value;

    @ValueMapValue
    private String summary;

    @ValueMapValue
    private String[] sources;

    @ValueMapValue
    private Double confidence;

    /**
     * The topic this assesses.
     *
     * @return the topic; {@code null} if none is set, or this session cannot resolve it
     */
    @Nullable
    public Topic getTopic()
    {
        return this.getReference(this.topic, Topic.class);
    }

    /**
     * The verdict on the topic.
     *
     * @return the verdict; {@code null} if none is given yet
     */
    @Nullable
    public String getValue()
    {
        return this.value;
    }

    /**
     * A short summary of this assessment.
     *
     * @return the summary; {@code null} if none was given
     */
    @Nullable
    public String getSummary()
    {
        return this.summary;
    }

    /**
     * What the assessment read: document revisions and answers. A newer revision or a changed answer makes it
     * stale. The link is weak, so one removed since is skipped.
     *
     * @return the parts of the submission read; empty if none are recorded or none of them resolve
     */
    @NotNull
    public List<EntityPart> getSources()
    {
        if (this.sources == null) {
            return List.of();
        }
        return Arrays.stream(this.sources)
            .map(identifier -> this.getReference(identifier, EntityPart.class))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    /**
     * How sure an automated assessment is.
     *
     * @return a value from 0 to 1; {@code null} if none was given
     */
    @Nullable
    public Double getConfidence()
    {
        return this.confidence;
    }

    /**
     * The comments raised about this topic, in the order they were raised.
     *
     * @return the comments; empty if there are none
     */
    @NotNull
    public List<Comment> getComments()
    {
        return this.getChildren(Comment.RESOURCE_TYPE, Comment.class);
    }

    @Override
    @NotNull
    public String getIdentifier()
    {
        return this.identifier;
    }
}
