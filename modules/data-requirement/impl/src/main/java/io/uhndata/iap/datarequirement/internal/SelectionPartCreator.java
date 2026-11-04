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
package io.uhndata.iap.datarequirement.internal;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.datarequirement.models.DataRequirement;
import io.uhndata.iap.datarequirement.models.Selection;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.PartCreator;
import io.uhndata.iap.utils.ReferenceUtils;
import io.uhndata.iap.utils.VersioningUtils;

/**
 * Gives each data requirement that applies an empty selection of its own, so that what is still missing has a part
 * to be marked on.
 *
 * <p>The selection is bound to no catalogue version until its first save.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = PartCreator.class)
public class SelectionPartCreator implements PartCreator
{
    @Reference
    private ConditionEvaluator conditions;

    @Override
    public void createMissing(final Resource submission) throws PersistenceException
    {
        final Submission model = Objects.requireNonNull(submission.adaptTo(Submission.class),
            "Only a submission has parts");
        for (final DataRequirement requirement : Selections.getDataRequirements(model, this.conditions)) {
            if (Selections.findSelection(model, requirement) == null) {
                createSelection(submission, requirement);
            }
        }
    }

    /**
     * Creates the empty selection for a requirement.
     *
     * @param submission the submission's resource, through the session writing it
     * @param requirement the requirement the selection answers
     * @throws PersistenceException when it cannot be written
     */
    private static void createSelection(final Resource submission, final DataRequirement requirement)
        throws PersistenceException
    {
        VersioningUtils.checkOut(submission);
        final Resource selection = submission.getResourceResolver().create(submission, UUID.randomUUID().toString(),
            Map.of("jcr:primaryType", Selection.NODE_TYPE));
        ReferenceUtils.setReference(selection, "fulfills", requirement);
    }
}
