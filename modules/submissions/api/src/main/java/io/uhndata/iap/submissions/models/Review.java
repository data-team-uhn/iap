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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.tags.models.Taggable;

/**
 * A Sling Model wrapping a {@code sub:Review} node: a reviewer's binding {@link Feedback} on the submission, which
 * addresses a requirement and can grant it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = {Review.class, Feedback.class}, resourceType = Review.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Review extends Feedback
{
    /** The {@code sling:resourceType} of a {@code sub:Review} node. */
    public static final String RESOURCE_TYPE = "sub/Review";

    /** The {@code review} tag a review carries once its reviewer has turned the requirement down. */
    public static final String REJECTED_TAG = "rejected";

    @ValueMapValue
    private String reviewer;

    @ValueMapValue
    private String requirement;

    /**
     * Who wrote this review.
     *
     * @return the reviewer's user id
     */
    @NotNull
    public String getReviewer()
    {
        return this.reviewer;
    }

    /**
     * The requirement this review addresses, usually an {@code ApprovalRequirement}.
     *
     * @return the requirement; {@code null} if this review addresses no requirement, or this session cannot
     *         resolve it
     */
    @Nullable
    public Requirement getRequirement()
    {
        return this.getReference(this.requirement, Requirement.class);
    }

    /**
     * Whether this review approved what it addresses: it carries the {@code approved} tag itself. A tag on the
     * submission it belongs to does not count.
     *
     * @return {@code true} if approved; {@code false} also when the tags service is unavailable
     */
    public boolean isApproved()
    {
        final Taggable tags = this.as(Taggable.class);
        return tags != null && tags.hasOwnTag(Submission.APPROVED_TAG);
    }

    /**
     * Whether this review has reached a decision, either way. A review that is neither approved nor rejected is still
     * open: its reviewer may be at work on it, or have asked for changes.
     *
     * @return {@code true} if approved or rejected; {@code false} also when the tags service is unavailable
     */
    public boolean isDecided()
    {
        final Taggable tags = this.as(Taggable.class);
        return tags != null && (tags.hasOwnTag(Submission.APPROVED_TAG) || tags.hasOwnTag(REJECTED_TAG));
    }

    /**
     * Whether this review addresses the given requirement. Requirements are compared by path, since a requirement of
     * another schema version is a different requirement even when it has the same name.
     *
     * @param requirement the requirement to compare with
     * @return {@code true} if this review says it addresses that requirement
     */
    public boolean isAbout(@NotNull final Requirement requirement)
    {
        final Requirement reviewed = this.getRequirement();
        return reviewed != null && requirement.getPath().equals(reviewed.getPath());
    }
}
