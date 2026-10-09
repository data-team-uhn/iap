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
import java.util.stream.Stream;

import org.apache.sling.models.annotations.injectorspecific.ValueMapValue;
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

    @ValueMapValue
    private boolean binding;

    /**
     * Whether this feedback decides anything, as a reviewer's review does. Each kind of feedback says so itself, so
     * a new binding kind counts without code naming it.
     *
     * @return {@code true} for binding feedback
     */
    public boolean isBinding()
    {
        return this.binding;
    }

    /**
     * Every comment raised in this feedback, in the order they were added.
     *
     * @return a list of comments, empty if none
     */
    @NotNull
    public List<Comment> getComments()
    {
        return this.getChildren(Comment.RESOURCE_TYPE, Comment.class);
    }

    /**
     * The comments raised in this feedback, those on its assessments included, that the submitter has not yet
     * addressed.
     *
     * @return a list of unresolved comments, empty if none
     */
    @NotNull
    public List<Comment> getUnresolvedComments()
    {
        final Stream<Comment> onAssessments = this.getAssessments().stream()
            .flatMap(assessment -> assessment.getComments().stream());
        return Stream.concat(this.getComments().stream(), onAssessments)
            .filter(comment -> !comment.isResolved())
            .collect(Collectors.toList());
    }

    /**
     * The assessments of the topics the approval requirement lists, in the order they were added.
     *
     * @return a list of assessments, empty if none
     */
    @NotNull
    public List<Assessment> getAssessments()
    {
        return this.getChildren(Assessment.RESOURCE_TYPE, Assessment.class);
    }
}
