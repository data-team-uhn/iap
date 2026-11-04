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
package io.uhndata.iap.submissions.internal.describers;

import java.util.Objects;
import java.util.Optional;

import jakarta.json.Json;
import jakarta.json.JsonObjectBuilder;

import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.ApprovalRequirement;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.submissions.models.Review;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.RequirementDescriber;
import io.uhndata.iap.utils.DateUtils;

/**
 * What an approval requirement adds to a form: who decides it, and the latest decision once somebody has made one.
 *
 * <p>Nobody grants an approval from the form, so where it stands is all the form can say about it. Which review
 * counts as the decision is the submission's to say. Until there is one, there is no decision to report, whatever
 * reviews are still open.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = RequirementDescriber.class)
public class ApprovalRequirementDescriber implements RequirementDescriber
{
    private static final String APPROVER_GROUP_KEY = "approverGroup";

    private static final String DECISION_KEY = "decision";

    private static final String APPROVED_KEY = "approved";

    private static final String DECIDED_BY_KEY = "by";

    private static final String DECIDED_AT_KEY = "at";

    @Override
    public boolean handles(final Requirement requirement)
    {
        return requirement instanceof ApprovalRequirement;
    }

    @Override
    public void describe(final Requirement requirement, final Submission submission, final JsonObjectBuilder json)
    {
        final ApprovalRequirement approval = (ApprovalRequirement) requirement;
        // Stated always, empty when the approval is not narrowed to a group
        json.add(APPROVER_GROUP_KEY, Objects.toString(approval.getApproverGroup(), ""));
        Optional.ofNullable(submission.findLatestDecisionOn(approval))
            .ifPresent(review -> json.add(DECISION_KEY, decision(review)));
    }

    /**
     * One decision on an approval: which way it went, who made it, and when, if the review records that.
     *
     * @param review the review that decided
     * @return the decision's JSON
     */
    private static JsonObjectBuilder decision(final Review review)
    {
        final JsonObjectBuilder json = Json.createObjectBuilder()
            .add(APPROVED_KEY, review.isApproved())
            .add(DECIDED_BY_KEY, review.getReviewer());
        Optional.ofNullable(DateUtils.toString(review.getCreated())).ifPresent(at -> json.add(DECIDED_AT_KEY, at));
        return json;
    }
}
