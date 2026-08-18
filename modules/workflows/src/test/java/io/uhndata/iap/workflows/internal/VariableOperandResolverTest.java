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

import java.util.Map;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.conditions.api.Operand;
import io.uhndata.iap.conditions.models.ConditionOperand;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.workflows.models.WorkflowFixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link VariableOperandResolver}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class VariableOperandResolverTest
{
    private static final String TYPE = "sling:resourceType";

    private static final String INSTANCE = "/Submissions/request/wf:instances/timeOffRequest";

    private final SlingContext context = new SlingContext();

    private final VariableOperandResolver resolver = new VariableOperandResolver();

    @BeforeEach
    void setUp()
    {
        WorkflowFixture.setUp(this.context);
        this.context.create().resource(INSTANCE, Map.of(
            TYPE, "wf/WorkflowInstance", "status", "active"));
    }

    @Test
    void namesItsSource()
    {
        assertEquals("variable", this.resolver.getSource());
    }

    @Test
    void resolvesAVariableOfTheInstance()
    {
        this.variable("outcome", "string", "stringValue", "approved");

        assertEquals("approved", this.resolve("outcome", INSTANCE).get(0));
    }

    @Test
    void resolvesTheStoredTypeRatherThanItsText()
    {
        // The variable's dataType decides which typed property its value came from, and that type is what the
        // evaluator unifies against the other side
        this.variable("requestedDays", "long", "longValue", 7L);

        assertEquals(7L, this.resolve("requestedDays", INSTANCE).get(0));
    }

    @Test
    void reportsAnUnsetVariableAsEmpty()
    {
        // Empty rather than absent, so that a condition can test for it with "is empty"
        assertTrue(this.resolve("outcome", INSTANCE).isEmpty());
    }

    @Test
    void reportsAnOperandNamingNothingAsEmpty()
    {
        final Resource operand = this.context.create().resource("/operand", TYPE, "cond/ConditionOperand");
        final Content instance = this.context.resourceResolver().getResource(INSTANCE).adaptTo(Content.class);

        assertTrue(this.resolver.resolve(operand.adaptTo(ConditionOperand.class), instance).isEmpty());
    }

    @Test
    void reportsAnythingThatIsNotAnInstanceAsEmpty()
    {
        // A condition written for a workflow can be evaluated against anything; what it asks about is simply
        // not there
        this.context.create().resource("/Submissions/request/outcome", Map.of(
            TYPE, "wf/Variable", "dataType", "string", "stringValue", "approved"));

        assertTrue(this.resolve("outcome", "/Submissions/request").isEmpty());
    }

    private void variable(final String name, final String dataType, final String property, final Object value)
    {
        this.context.create().resource(INSTANCE + "/" + name, Map.of(
            TYPE, "wf/Variable", "dataType", dataType, property, value));
    }

    private Operand resolve(final String variableName, final String contextPath)
    {
        final Resource operand = this.context.create().resource("/operand-" + variableName + contextPath, Map.of(
            TYPE, "cond/ConditionOperand", "source", "variable", "value", variableName));
        final Content evaluationContext =
            this.context.resourceResolver().getResource(contextPath).adaptTo(Content.class);
        return this.resolver.resolve(operand.adaptTo(ConditionOperand.class), evaluationContext);
    }
}
