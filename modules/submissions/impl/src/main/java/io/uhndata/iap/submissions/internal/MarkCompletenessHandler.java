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

import java.util.Objects;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.tags.models.Taggable;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.api.WorkflowResult;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * The service task that records whether a submission is still missing an answer it is asked for: what the save
 * workflow performs once the answers are written and accepted.
 *
 * <p>The state is a tag rather than a computation done wherever it is needed, so that everything already reading a
 * submission can see it: the dashboard, which lists submissions without asking for their forms; the control that
 * offers to send one, which must not offer it while something is missing; and a workflow, which can route on tags
 * through the conditions it already has. It is a system tag, so nobody can hand-place or remove it through the
 * user-facing APIs — it is derived, and the only honest way to change it is to answer the question.</p>
 *
 * <p>Kept true by being recomputed on every save, which is the only thing that writes answers: filling a submission
 * in is a workflow, so there is no second writer to fall out of step with. It runs <em>after</em> the validators,
 * because a save that is going to be refused should leave no trace, and the whole run is one commit.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class MarkCompletenessHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String NAME = "markCompleteness";

    /** The tag saying that something the submission is asked for has not been answered. */
    public static final String INCOMPLETE = "incomplete";

    @Reference
    private ConditionEvaluator conditions;

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource target = subject(context);
        final Submission submission = Objects.requireNonNull(target.adaptTo(Submission.class),
            "This task only applies to submissions");
        // Every resource adapts to Taggable — the mixin decides what may be tagged, not the model
        final Taggable taggable = Objects.requireNonNull(target.adaptTo(Taggable.class),
            "Any resource can be read as taggable content");
        if (new FormCompleteness(this.conditions).isIncomplete(submission)) {
            taggable.tag(INCOMPLETE, true);
        } else {
            // Unconditionally, rather than only when it is there: untag answers "make sure it is not carried", and
            // the alternative is reading the tag first to decide whether to remove it
            taggable.untag(INCOMPLETE, true);
        }
    }

    /**
     * The submission this task is marking: what a previous activity created, or failing that the event's own target.
     *
     * <p>The save workflow posts to the submission itself, so the target <em>is</em> the subject. The create
     * workflow posts to the homepage and only then has a submission, which it records as
     * {@link WorkflowResult#CREATED_PATH} — so the same handler serves both, and a new submission is tagged from
     * birth rather than from its first save. That distinction matters: until something writes the tag, its absence
     * is indistinguishable from being complete, and a control offering to send the request reads that absence as
     * permission.</p>
     *
     * <p>The same rule, in the same shape, is what {@code WorkflowStarter} uses to find the entity to attach a
     * workflow to. It is asked twice now and belongs on {@link WorkflowTaskContext} rather than in each handler;
     * moving it there is an SPI change and is deliberately not smuggled in here.</p>
     *
     * @param context the executing task's context
     * @return the resource to judge
     * @throws WorkflowDefinitionException when a path was recorded but leads nowhere
     */
    private static Resource subject(final WorkflowTaskContext context) throws WorkflowDefinitionException
    {
        final Object created = context.getVariable(WorkflowResult.CREATED_PATH);
        if (!(created instanceof String)) {
            return context.getTarget();
        }
        final Resource subject = context.getResourceResolver().getResource((String) created);
        if (subject == null) {
            throw new WorkflowDefinitionException("Nothing was created at " + created + " to judge");
        }
        return subject;
    }
}
