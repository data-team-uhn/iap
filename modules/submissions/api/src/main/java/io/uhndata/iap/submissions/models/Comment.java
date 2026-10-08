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

/**
 * A Sling Model wrapping a {@code sub:Comment} node: a comment or question raised in some feedback, a concern the AI
 * found among them, with what it is about and the context that shows it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Comment.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Comment extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code sub:Comment} node. */
    public static final String RESOURCE_TYPE = "sub/Comment";

    @ValueMapValue
    private String text;

    @ValueMapValue
    private String author;

    @ValueMapValue
    private String[] subjects;

    @ValueMapValue
    private String kind;

    @ValueMapValue
    private String suggestion;

    @ValueMapValue
    private boolean resolved;

    /**
     * The comment text.
     *
     * @return the comment text
     */
    @NotNull
    public String getText()
    {
        return this.text;
    }

    /**
     * Identifies who wrote this comment. Not necessarily the same as {@code jcr:createdBy}: comments
     * and replies can originate from an external site, created here by an integration service user on the actual
     * author's behalf.
     *
     * @return a principal name, or an external identifier
     */
    @NotNull
    public String getAuthor()
    {
        return this.author;
    }

    /**
     * What this comment is about: answers or documents of the submission, or the questions and requirements of its
     * schema. The link is weak, so one removed since is skipped.
     *
     * @return the parts concerned, empty for a general comment or when none of them resolve
     */
    @NotNull
    public List<EntityPart> getSubjects()
    {
        if (this.subjects == null) {
            return List.of();
        }
        return Arrays.stream(this.subjects)
            .map(identifier -> this.getReference(identifier, EntityPart.class))
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    /**
     * What kind of point this comment makes, e.g. a gap, a conflict or a concern.
     *
     * @return the kind, or {@code null} if not said
     */
    @Nullable
    public String getKind()
    {
        return this.kind;
    }

    /**
     * What would address this comment, when its author can say.
     *
     * @return a suggestion, or {@code null} if there is none
     */
    @Nullable
    public String getSuggestion()
    {
        return this.suggestion;
    }

    /**
     * The context that shows what this comment is about, in the order it was quoted.
     *
     * @return a list of context, empty if none
     */
    @NotNull
    public List<Context> getContext()
    {
        return this.getChildren(Context.RESOURCE_TYPE, Context.class);
    }

    /**
     * Whether the submitter has addressed this comment.
     *
     * @return {@code true} if resolved
     */
    public boolean isResolved()
    {
        return this.resolved;
    }

    /**
     * The discussion thread attached to this comment, in chronological order.
     *
     * @return a list of replies, empty if none
     */
    @NotNull
    public List<Reply> getReplies()
    {
        return this.getChildren(Reply.RESOURCE_TYPE, Reply.class);
    }
}
