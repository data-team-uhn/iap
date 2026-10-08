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

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;

import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.CompletenessEvaluator;
import io.uhndata.iap.tags.models.Taggable;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ExecutionHost;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that marks the parts of a submission still lacking something its author has to supply, with the
 * {@code incomplete} tag.
 *
 * <p>Every registered {@link CompletenessEvaluator} judges its own kind of part. The parts they report are tagged,
 * and the submission's other parts lose the tag. The tag is aggregated, so the submission carries it while any of
 * its parts does.</p>
 *
 * <p>It acts on the submission an earlier step of the run created, if there is one, and otherwise on the event's
 * target. So the create workflow marks a new submission from the start, and the save and attach workflows mark the
 * submission they changed.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class MarkCompletenessHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "markCompleteness";

    /** The tag saying that something a part of a submission is asked for has not been supplied. */
    public static final String INCOMPLETE_TAG = "incomplete";

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC,
        policyOption = ReferencePolicyOption.GREEDY)
    private volatile List<CompletenessEvaluator> evaluators;

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource target = ExecutionHost.of(context);
        if (!target.isResourceType(Submission.RESOURCE_TYPE)) {
            throw new WorkflowDefinitionException("The markCompleteness task only applies to submissions, not to "
                + target.getResourceType());
        }
        final Set<String> incomplete = new HashSet<>();
        for (final CompletenessEvaluator evaluator : this.evaluators) {
            for (final Resource part : evaluator.evaluate(target)) {
                incomplete.add(part.getPath());
                taggable(part).tag(INCOMPLETE_TAG, true);
            }
        }
        for (final Resource part : target.getChildren()) {
            final Taggable taggable = part.adaptTo(Taggable.class);
            if (taggable != null && !incomplete.contains(part.getPath()) && taggable.hasOwnTag(INCOMPLETE_TAG)) {
                taggable.untag(INCOMPLETE_TAG, true);
            }
        }
    }

    private static Taggable taggable(final Resource part)
    {
        return Objects.requireNonNull(part.adaptTo(Taggable.class), "A part of a submission can be tagged");
    }
}
