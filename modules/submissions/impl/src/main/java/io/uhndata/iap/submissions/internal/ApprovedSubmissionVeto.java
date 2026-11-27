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

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;

import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.deletion.spi.DeletionMode;
import io.uhndata.iap.deletion.spi.DeletionVeto;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.tags.api.TagManager;

/**
 * Refuses every deletion of an approved submission: one that carries the {@code approved} tag itself. An approval is
 * a decision on record, and the submission is what it was made about. Nobody may delete it, administrators included.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(immediate = true)
public class ApprovedSubmissionVeto implements DeletionVeto
{
    private static final String SUBMISSION_TYPE = "sub:Submission";

    @Override
    public String getName()
    {
        return "approvedSubmission";
    }

    @Override
    public String veto(final Node node, final DeletionMode mode, final Session requester)
        throws RepositoryException
    {
        if (node.isNodeType(SUBMISSION_TYPE) && isApproved(node)) {
            return "An approved submission cannot be deleted";
        }
        return null;
    }

    private static boolean isApproved(final Node node) throws RepositoryException
    {
        if (!node.hasProperty(TagManager.TAGS_PROPERTY)) {
            return false;
        }
        // A loop, since reading a value can throw a checked exception
        for (final Value tag : node.getProperty(TagManager.TAGS_PROPERTY).getValues()) {
            if (Submission.APPROVED_TAG.equals(tag.getString())) {
                return true;
            }
        }
        return false;
    }
}
