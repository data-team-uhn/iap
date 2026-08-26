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
package io.uhndata.iap.workflows.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.workflows.api.WorkflowConflictException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Moves a workflow version to another state of its lifecycle. Which move this is, is the activity's configuration
 * rather than the payload's: {@code toState} is where the version ends up, and {@code fromStates} are the states
 * it may be moved there from.
 *
 * <p><strong>The lifecycle table lives in content.</strong> A version's states are four and their permitted moves
 * are five, and every one of them used to be a row of a map in Java — which meant that saying "an author may
 * return their own trial to a draft, but only an administrator may activate one" could not be said at all, since
 * one endpoint served every move and one property could only admit one set of people. Now each move is a system
 * workflow of its own, naming its own performers, and this handler is the step those workflows have in common. A
 * fifth state is a new definition rather than a new row here.</p>
 *
 * <p>Refusing when the version is not in one of {@code fromStates} is what keeps the moves that do not exist from
 * happening: an active version is retired by another being promoted in its place and a retired one is carried
 * forward by drafting a copy, so neither is listed as a state anything moves out of.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class SetVersionStateHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String NAME = "setVersionState";

    /** The activity property naming the state a version is moved to. */
    private static final String TO_STATE = "toState";

    /** The activity property naming the states it may be moved there from. */
    private static final String FROM_STATES = "fromStates";

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final WorkflowVersion version = VersionEdits.targetVersion(context);
        final WorkflowVersion.State target = state(context, TO_STATE);
        final List<WorkflowVersion.State> allowed = states(context, FROM_STATES);
        if (allowed.isEmpty()) {
            throw new WorkflowDefinitionException("The activity " + context.getActivity().getPath()
                + " does not list which " + FROM_STATES + " this move is available from");
        }
        final WorkflowVersion.State current = version.getState();
        // The message says which versions this move is for, since a client that asked for it is usually looking at
        // a version whose state has moved on since the buttons were drawn
        if (!allowed.contains(current)) {
            throw new WorkflowConflictException("A " + VersionEdits.name(current) + " version cannot be made "
                + VersionEdits.name(target) + "; that is only available for a " + names(allowed) + " version");
        }
        Objects.requireNonNull(context.getTarget().adaptTo(ModifiableValueMap.class),
            "The engine can always write what it can read").put(VersionEdits.STATE, target.name());
    }

    /**
     * One state named by the activity's configuration.
     *
     * @param context the handler's context
     * @param property the configuration property to read
     * @return the state it names
     * @throws WorkflowDefinitionException when it names nothing, or nothing that is a state
     */
    private static WorkflowVersion.State state(final WorkflowTaskContext context, final String property)
        throws WorkflowDefinitionException
    {
        final Object configured = context.getActivity().get(property);
        if (configured instanceof String) {
            try {
                return WorkflowVersion.State.valueOf(((String) configured).toUpperCase(Locale.ROOT));
            } catch (final IllegalArgumentException e) {
                // Falls through to the same complaint as a missing one: either way the definition does not say
                // where this move goes
            }
        }
        throw new WorkflowDefinitionException("The activity " + context.getActivity().getPath() + " does not name a "
            + property + "; it must be one of " + names(Arrays.asList(WorkflowVersion.State.values())));
    }

    /**
     * The states named by one of the activity's list-valued configuration properties, tolerating the single-valued
     * form a JCR property collapses to when it was authored with one entry.
     *
     * @param context the handler's context
     * @param property the configuration property to read
     * @return the states it names, in the order it names them
     * @throws WorkflowDefinitionException when one of the names is not a state
     */
    private static List<WorkflowVersion.State> states(final WorkflowTaskContext context, final String property)
        throws WorkflowDefinitionException
    {
        final Object configured = context.getActivity().get(property);
        final List<String> named;
        if (configured instanceof String[]) {
            named = Arrays.asList((String[]) configured);
        } else {
            named = configured instanceof String ? List.of((String) configured) : List.of();
        }
        final List<WorkflowVersion.State> states = new ArrayList<>(named.size());
        for (final String name : named) {
            try {
                states.add(WorkflowVersion.State.valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (final IllegalArgumentException e) {
                throw new WorkflowDefinitionException("The activity " + context.getActivity().getPath()
                    + " lists " + name + " in " + property + ", which is not one of "
                    + names(Arrays.asList(WorkflowVersion.State.values())));
            }
        }
        return states;
    }

    /**
     * Several states, as they read in a sentence written for a person.
     *
     * @param states the states to name, in the order they should be listed
     * @return their names in lower case, comma-separated, e.g. {@code draft, trial}
     */
    private static String names(final List<WorkflowVersion.State> states)
    {
        return states.stream().map(VersionEdits::name).collect(Collectors.joining(" or "));
    }
}
