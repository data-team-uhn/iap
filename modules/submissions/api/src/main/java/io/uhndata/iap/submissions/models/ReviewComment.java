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

import io.uhndata.iap.entities.models.EntityPart;

/**
 * A Sling Model wrapping a {@code sub:ReviewComment} node: a single comment or question raised by a reviewer.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = ReviewComment.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class ReviewComment extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code sub:ReviewComment} node. */
    public static final String RESOURCE_TYPE = "sub/ReviewComment";

    @ValueMapValue
    private String text;

    @ValueMapValue
    private String author;

    @ValueMapValue
    private String subject;

    @ValueMapValue
    private String selectionStart;

    @ValueMapValue
    private String selectionEnd;

    @ValueMapValue
    private boolean resolved;

    /**
     * The comment text.
     *
     * @return the comment text
     */
    public String getText()
    {
        return this.text;
    }

    /**
     * Identifies the reviewer who wrote this comment. Not necessarily the same as {@code jcr:createdBy}: comments
     * and replies can originate from an external site, created here by an integration service user on the actual
     * author's behalf.
     *
     * @return a principal name, or an external identifier
     */
    public String getAuthor()
    {
        return this.author;
    }

    /**
     * The identifier of the part of the submission this comment is about, e.g. a {@code sub:Answer} or a
     * {@code sub:Document}.
     *
     * @return an UUID, or {@code null} if this is a general comment not tied to a specific part
     */
    public String getSubject()
    {
        return this.subject;
    }

    /**
     * The start of the anchor narrowing this comment down to a specific selection within the subject.
     *
     * @return a flexible, URI-like anchor, or {@code null} if this comment targets the whole subject
     */
    public String getSelectionStart()
    {
        return this.selectionStart;
    }

    /**
     * The end of the anchor narrowing this comment down to a specific selection within the subject.
     *
     * @return a flexible, URI-like anchor, or {@code null} if this comment targets the whole subject
     */
    public String getSelectionEnd()
    {
        return this.selectionEnd;
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
    public List<Reply> getReplies()
    {
        return this.getChildren(Reply.RESOURCE_TYPE, Reply.class);
    }
}
