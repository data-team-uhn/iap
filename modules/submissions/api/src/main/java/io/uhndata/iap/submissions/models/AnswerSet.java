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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.Fulfiller;
import io.uhndata.iap.schemas.models.Requirement;

/**
 * A Sling Model wrapping a {@code sub:AnswerSet} node: the answers given for one set of questions, and the thing
 * that says which set they are for.
 *
 * <p>An answer names a <em>question</em>, so before this a form requirement was the one kind that could not say
 * what answered it — every other kind is met by a part carrying {@code fulfills}, and the walk over
 * {@link Fulfiller}s had to special-case questions. Grouping the answers under something that names the
 * requirement makes them no different from a document or a decision.</p>
 *
 * <p><strong>Whether the set meets its requirement is not a question it can answer alone</strong>, which is worth
 * knowing before reading {@link #isFulfilling}. What is demanded depends on which questions apply, and a
 * question applies or not according to a condition evaluated against the whole submission — so the answer lives
 * with the submission and this delegates to it. The grouping is real; the arithmetic did not move.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, adapters = { AnswerSet.class, Fulfiller.class },
    resourceType = AnswerSet.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class AnswerSet extends Fulfiller
{
    /** The {@code sling:resourceType} of a {@code sub:AnswerSet} node. */
    public static final String RESOURCE_TYPE = "sub/AnswerSet";

    @ValueMapValue
    private String fulfills;

    /**
     * The set of questions these answers were given for.
     *
     * @return a requirement, or {@code null} if it names none or the reference cannot be resolved
     */
    @Nullable
    @Override
    public Requirement getFulfills()
    {
        return this.getReference(this.fulfills, Requirement.class);
    }

    /**
     * The answers in this set.
     *
     * @return a list of answers, empty if none have been given
     */
    @NotNull
    public List<Answer> getAnswers()
    {
        return this.getChildren(Answer.RESOURCE_TYPE, Answer.class);
    }

    /**
     * Whether every question this set is for that demands an answer has one.
     *
     * <p>Delegated to the submission, and it has to be: which questions are being asked depends on conditions
     * evaluated against the submission as a whole, so a set of answers cannot tell on its own what was demanded
     * of it. A set hanging outside a submission, or naming something that is not a set of questions, meets
     * nothing — there is no context in which to judge it.</p>
     *
     * @return {@code true} if nothing more is owed for the requirement this answers
     */
    @Override
    public boolean isFulfilling()
    {
        final Requirement named = this.getFulfills();
        if (!(named instanceof FormRequirement)) {
            return false;
        }
        final Submission submission = this.getParent(Submission.RESOURCE_TYPE, Submission.class);
        return submission != null && submission.isFullyAnswered((FormRequirement) named);
    }
}
