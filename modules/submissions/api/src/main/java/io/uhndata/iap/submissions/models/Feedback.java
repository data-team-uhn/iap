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

import org.jetbrains.annotations.NotNull;

import io.uhndata.iap.entities.models.EntityPart;

/**
 * The base of the Sling Models wrapping the kinds of {@code sub:Feedback} on a submission: a reviewer's binding
 * {@link Review}, the AI's {@link Screening}, and a {@link Discussion} that binds nothing. Each subtype adapts to
 * this class as well, so reading a node as {@code Feedback} gives the model of its own kind.
 *
 * @version $Id$
 * @since 0.1.0
 */
public abstract class Feedback extends EntityPart
{
    /** The {@code sling:resourceSuperType} of every kind of {@code sub:Feedback} node. */
    public static final String RESOURCE_TYPE = "sub/Feedback";

    /**
     * Every comment raised in this feedback, in the order they were added.
     *
     * @return a list of comments, empty if none
     */
    @NotNull
    public List<ReviewComment> getComments()
    {
        return this.getChildren(ReviewComment.RESOURCE_TYPE, ReviewComment.class);
    }

    /**
     * The comments raised in this feedback that the submitter has not yet addressed.
     *
     * @return a list of unresolved comments, empty if none
     */
    @NotNull
    public List<ReviewComment> getUnresolvedComments()
    {
        return this.getComments().stream()
            .filter(comment -> !comment.isResolved())
            .collect(Collectors.toList());
    }

    /**
     * The assessments of the criteria the approval requirement lists, in the order they were added.
     *
     * @return a list of assessments, empty if none
     */
    @NotNull
    public List<Assessment> getAssessments()
    {
        return this.getChildren(Assessment.RESOURCE_TYPE, Assessment.class);
    }
}
