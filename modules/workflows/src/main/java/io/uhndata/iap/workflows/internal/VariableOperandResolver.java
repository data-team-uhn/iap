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

import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.conditions.api.Operand;
import io.uhndata.iap.conditions.models.ConditionOperand;
import io.uhndata.iap.conditions.spi.OperandResolver;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.workflows.models.Variable;
import io.uhndata.iap.workflows.models.WorkflowInstance;

/**
 * Resolves {@code variable} operands: the variable named by the operand value, read from the workflow instance the
 * condition is evaluated against. A gateway routes this way on the {@code outcome} the last completed task recorded.
 *
 * <p>This lets a condition read a workflow's own state. Variables live on the instance rather than in content, so a
 * condition that reads one is evaluated against the instance.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component
public class VariableOperandResolver implements OperandResolver
{
    private static final Logger LOGGER = LoggerFactory.getLogger(VariableOperandResolver.class);

    @Override
    public String getSource()
    {
        return "variable";
    }

    @Override
    public Operand resolve(final ConditionOperand operand, final Content context)
    {
        final String[] value = operand.getValue();
        if (value == null || value.length == 0) {
            LOGGER.warn("Variable operand at {} does not name a variable", operand.getPath());
            return Operand.EMPTY;
        }
        // Check the resource type, not just the adaptation. Adapting does not filter by type: a model registered for
        // one type is handed back for any resource when nothing else claims the class. An unrelated node with a child
        // of the right name would otherwise answer as if it were an instance.
        final WorkflowInstance instance = WorkflowInstance.RESOURCE_TYPE.equals(context.getType())
            ? context.as(WorkflowInstance.class) : null;
        if (instance == null) {
            // Not a definition error: the condition was evaluated against something that has no variables
            LOGGER.warn("Variable operand at {} was evaluated against {}, which is not a workflow instance",
                operand.getPath(), context.getPath());
            return Operand.EMPTY;
        }
        final Variable variable = instance.getVariable(value[0]);
        // No declared type: the stored value already has the type the variable's dataType chose
        return variable == null ? Operand.EMPTY : Operand.of(variable.getValue());
    }
}
