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
package io.uhndata.iap.submissions.internal;

import java.util.Calendar;
import java.util.List;
import java.util.Objects;

import jakarta.json.JsonObjectBuilder;

import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.ApprovalRequirement;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.submissions.models.Review;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.RequirementDescriber;
import io.uhndata.iap.utils.DateUtils;

/**
 * What an approval requirement adds to a form: who it waits on, and the decision once somebody has made one.
 *
 * <p>Nobody fills an approval in on the form, so what it can offer is an honest account of where the approval
 * stands. That is worth projecting rather than leaving the reader to infer it, because the alternative — a section
 * that says only that it cannot be completed here — is indistinguishable from a part of the form that is
 * broken.</p>
 *
 * <p>Approved is the model's own predicate, an approved review naming this requirement, so the form and the
 * completeness tag cannot disagree about what an approval means. The decision is reported from the same review; a
 * rejection is a review that is not approved, which is why the reviewer and the date are given whenever a review
 * exists rather than only when it granted the approval.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = RequirementDescriber.class)
public class ApprovalRequirementDescriber implements RequirementDescriber
{
    @Override
    public boolean handles(final Requirement requirement)
    {
        return requirement instanceof ApprovalRequirement;
    }

    @Override
    public void describe(final Requirement generic, final Submission submission,
        final JsonObjectBuilder json)
    {
        final ApprovalRequirement requirement = (ApprovalRequirement) generic;
        // Always stated, empty meaning "not narrowed to a group": a reader has to tell that from "nobody has said
        // who decides", and both are things the section says out loud
        json.add("approverGroup", Objects.toString(requirement.getApproverGroup(), ""));
        final List<Review> reviews = submission.getReviewsOf(requirement);
        json.add("approved", reviews.stream().anyMatch(Review::isApproved));
        // The last word rather than the first: an approval that was revisited is reported as it now stands
        reviews.stream().reduce((first, second) -> second).ifPresent(review -> {
            json.add("decidedBy", Objects.toString(review.getReviewer(), ""));
            final Calendar decided = review.getCreated();
            if (decided != null) {
                // The same spelling the resource JSON uses for a date, so the reader parses one format
                json.add("decidedAt", DateUtils.PREFERRED_DATETIME_FORMAT
                    .format(decided.toInstant().atZone(decided.getTimeZone().toZoneId())));
            }
        });
    }
}
