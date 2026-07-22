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
import java.util.stream.Collectors;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;

import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.schemas.models.Requirement;

/**
 * A Sling Model wrapping a {@code sub:Review} node: one reviewer's assessment of the submission.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = Review.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class Review extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code sub:Review} node. */
    public static final String RESOURCE_TYPE = "sub/Review";

    @ValueMapValue
    private String reviewer;

    @ValueMapValue
    private String requirement;

    @ValueMapValue
    private String status;

    /**
     * The principal name of the reviewer.
     *
     * @return a principal name
     */
    public String getReviewer()
    {
        return this.reviewer;
    }

    /**
     * The requirement (typically an {@code ApprovalRequirement}) this review addresses.
     *
     * @return a requirement, or {@code null} if this review does not address a specific requirement, or it is
     *         unresolvable
     */
    public Requirement getRequirement()
    {
        return this.getReference(this.requirement, Requirement.class);
    }

    /**
     * The current state of this review, e.g. {@code in-progress}, {@code changes-requested}, {@code approved},
     * {@code rejected}.
     *
     * @return a status name
     */
    public String getStatus()
    {
        return this.status;
    }

    /**
     * Every comment raised during this review, in the order they were added.
     *
     * @return a list of review comments, empty if none
     */
    public List<ReviewComment> getComments()
    {
        return this.getChildren(ReviewComment.RESOURCE_TYPE, ReviewComment.class);
    }

    /**
     * The comments raised during this review that the submitter has not yet addressed.
     *
     * @return a list of unresolved review comments, empty if none
     */
    public List<ReviewComment> getUnresolvedComments()
    {
        return this.getComments().stream()
            .filter(comment -> !comment.isResolved())
            .collect(Collectors.toList());
    }
}
