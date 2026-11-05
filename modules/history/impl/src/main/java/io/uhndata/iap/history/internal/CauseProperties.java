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
package io.uhndata.iap.history.internal;

import java.util.AbstractMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.uhndata.iap.history.api.RecordedAction;
import io.uhndata.iap.history.models.Action;

/**
 * The text an action carries about its cause, each under the name of the {@code hist:Action} property that stores it.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class CauseProperties
{
    /** Each property's name, and how to read its value off an action; the value is {@code null} when not given. */
    private static final Map<String, Function<RecordedAction, String>> BY_NAME = Map.ofEntries(
        Map.entry(Action.CREATED_BY_PROPERTY, RecordedAction::getRequestedBy),
        Map.entry(Action.OPERATION_PROPERTY, RecordedAction::getOperation),
        Map.entry(Action.ON_BEHALF_OF_PROPERTY, RecordedAction::getOnBehalfOf),
        Map.entry(Action.WORKFLOW_INSTANCE_PROPERTY, RecordedAction::getWorkflowInstance),
        Map.entry(Action.WORKFLOW_VERSION_PROPERTY, RecordedAction::getWorkflowVersion),
        Map.entry(Action.ACTIVITY_ID_PROPERTY, RecordedAction::getActivityId),
        Map.entry(Action.ACTIVITY_LABEL_PROPERTY, RecordedAction::getActivityLabel),
        Map.entry(Action.TASK_INSTANCE_PROPERTY, RecordedAction::getTaskInstance),
        Map.entry(Action.OUTCOME_PROPERTY, RecordedAction::getOutcome),
        Map.entry(Action.OUTCOME_NOTE_PROPERTY, RecordedAction::getOutcomeNote),
        Map.entry(Action.EVENT_PROPERTY, RecordedAction::getEvent),
        Map.entry(Action.COMPONENT_PROPERTY, RecordedAction::getComponent),
        Map.entry(Action.PARENT_ACTION_PROPERTY, RecordedAction::getParentAction));

    private CauseProperties()
    {
        // Static methods only
    }

    /**
     * The text an action was given about its cause.
     *
     * @param action the action
     * @return each property given a value, mapped to that value
     */
    static Map<String, String> of(final RecordedAction action)
    {
        return BY_NAME.entrySet().stream()
            .map(property -> new AbstractMap.SimpleImmutableEntry<>(property.getKey(),
                property.getValue().apply(action)))
            .filter(property -> property.getValue() != null)
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
