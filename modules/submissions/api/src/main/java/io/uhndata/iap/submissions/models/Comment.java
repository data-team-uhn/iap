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
 * A Sling Model wrapping a {@code sub:Comment} node: a comment or a question raised in some feedback, such as a
 * concern the AI found. It holds what it is about and the context that shows it.
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
     * @return the text
     */
    @NotNull
    public String getText()
    {
        return this.text;
    }

    /**
     * Who wrote this comment. It can differ from {@code jcr:createdBy}: a comment or a reply can come from an
     * external site, created here by a service user on the author's behalf.
     *
     * @return a user id for a local author, or an identifier the external site gives
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
     * @return the parts concerned; empty for a general comment, or when none of them resolve
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
     * What kind of point this comment makes, such as a gap, a conflict or a concern.
     *
     * @return the kind; {@code null} if none is set
     */
    @Nullable
    public String getKind()
    {
        return this.kind;
    }

    /**
     * What would address this comment, when its author can say.
     *
     * @return the suggestion; {@code null} if there is none
     */
    @Nullable
    public String getSuggestion()
    {
        return this.suggestion;
    }

    /**
     * The context that shows what this comment is about, in the order it was quoted.
     *
     * @return the quotes; empty if there are none
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
     * The discussion thread attached to this comment, in the order the replies were added.
     *
     * @return the replies; empty if there are none
     */
    @NotNull
    public List<Reply> getReplies()
    {
        return this.getChildren(Reply.RESOURCE_TYPE, Reply.class);
    }
}
