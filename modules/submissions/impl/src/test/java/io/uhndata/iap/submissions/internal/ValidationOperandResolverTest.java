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

import java.util.HashMap;
import java.util.Map;

import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.conditions.api.Operand;
import io.uhndata.iap.conditions.models.ConditionOperand;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.schemas.models.ValidationRequirement;
import io.uhndata.iap.submissions.models.Extraction;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.models.Validation;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link ValidationOperandResolver}.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ValidationOperandResolverTest
{
    private static final String SLING_RESOURCE_TYPE = "sling:resourceType";

    private static final String VERSION_PATH = "/Schemas/schema/1.0";

    private static final String VERSION_ID = "11111111-2222-3333-4444-555555555555";

    private static final String REQUIREMENT_ID = "66666666-7777-8888-9999-000000000000";

    private static final String SUBMISSION_PATH = "/Submissions/sub";

    private static final String VALIDATION_PATH = SUBMISSION_PATH + "/proposal_check";

    private final SlingContext context = new SlingContext();

    private final ValidationOperandResolver resolver = new ValidationOperandResolver();

    private Content submission;

    private int operandCount;

    @BeforeEach
    void setUp()
        throws RepositoryException
    {
        this.context.addModelsForClasses(Content.class, EntityPart.class, ConditionOperand.class, Submission.class,
            SchemaVersion.class, ValidationRequirement.class, Validation.class, Extraction.class);
        this.context.create().resource(VERSION_PATH, SLING_RESOURCE_TYPE, SchemaVersion.RESOURCE_TYPE);
        this.context.create().resource(VERSION_PATH + "/is_proposal",
            SLING_RESOURCE_TYPE, ValidationRequirement.RESOURCE_TYPE);
        this.context.create().resource(VERSION_PATH + "/form", SLING_RESOURCE_TYPE, FormRequirement.RESOURCE_TYPE);
        final Session session = Mockito.mock(Session.class);
        mockNode(session, VERSION_ID, VERSION_PATH);
        mockNode(session, REQUIREMENT_ID, VERSION_PATH + "/is_proposal");
        Mockito.when(session.getNodeByIdentifier("gone")).thenThrow(new ItemNotFoundException());
        this.context.registerAdapter(ResourceResolver.class, Session.class, session);
        this.submission = this.createSubmission(VERSION_ID);
    }

    private static void mockNode(final Session session, final String identifier, final String path)
        throws RepositoryException
    {
        final Node node = Mockito.mock(Node.class);
        Mockito.when(node.getPath()).thenReturn(path);
        Mockito.when(session.getNodeByIdentifier(identifier)).thenReturn(node);
    }

    private Content createSubmission(final String version)
    {
        return this.context.create().resource(SUBMISSION_PATH, SLING_RESOURCE_TYPE, Submission.RESOURCE_TYPE,
            "schemaVersion", version).adaptTo(Content.class);
    }

    private ConditionOperand createOperand(final String... value)
    {
        final Map<String, Object> properties = new HashMap<>(Map.of(
            SLING_RESOURCE_TYPE, ConditionOperand.RESOURCE_TYPE, "source", "validation"));
        if (value != null) {
            properties.put("value", value);
        }
        return this.context.create().resource(
            "/Workflows/intake/v1/gateway" + this.operandCount++ + "/cond:condition/operandA", properties)
            .adaptTo(ConditionOperand.class);
    }

    private void createValidation(final String value, final double confidence)
    {
        this.context.create().resource(VALIDATION_PATH, SLING_RESOURCE_TYPE, Validation.RESOURCE_TYPE,
            "requirement", REQUIREMENT_ID);
        this.context.create().resource(VALIDATION_PATH + "/run", SLING_RESOURCE_TYPE, Extraction.RESOURCE_TYPE,
            "extractedValues", new String[] { value }, "confidence", confidence);
    }

    @Test
    void implementsTheValidationSource()
    {
        assertEquals("validation", this.resolver.getSource());
    }

    // The intake's gateway is evaluated in its workflow instance, which lives inside the submission
    @Test
    void readsTheOutcomeFromInsideTheSubmission()
    {
        this.createValidation("yes", 0.9);
        final Content instance = this.context.create().resource(SUBMISSION_PATH + "/wf:instances/intake",
            Map.of()).adaptTo(Content.class);

        final Operand resolved = this.resolver.resolve(this.createOperand("is_proposal"), instance);

        assertEquals(1, resolved.size());
        assertEquals("yes", resolved.get(0));
    }

    @Test
    void readsAnUnsureOutcome()
    {
        this.createValidation("no", 0.5);

        assertEquals("unsure", this.resolver.resolve(this.createOperand("is_proposal"), this.submission).get(0));
    }

    @Test
    void isEmptyBeforeTheModelIsAsked()
    {
        assertEquals(0, this.resolver.resolve(this.createOperand("is_proposal"), this.submission).size());
    }

    @Test
    void isEmptyWhileTheValidationHasNoRun()
    {
        this.context.create().resource(VALIDATION_PATH, SLING_RESOURCE_TYPE, Validation.RESOURCE_TYPE,
            "requirement", REQUIREMENT_ID);

        assertEquals(0, this.resolver.resolve(this.createOperand("is_proposal"), this.submission).size());
    }

    @Test
    void isEmptyOutsideASubmission()
    {
        final ConditionOperand operand = this.createOperand("is_proposal");

        assertEquals(0, this.resolver.resolve(operand, operand).size());
    }

    @Test
    void isEmptyWhenTheOperandNamesNothing()
    {
        assertEquals(0, this.resolver.resolve(this.createOperand((String[]) null), this.submission).size());
    }

    @Test
    void isEmptyWhenTheOperandValueIsAnEmptyList()
    {
        assertEquals(0, this.resolver.resolve(this.createOperand(), this.submission).size());
    }

    // A form requirement with the same name is not what the operand asks for
    @Test
    void isEmptyWhenThePathIsNoValidationRequirement()
    {
        this.createValidation("yes", 0.9);

        assertEquals(0, this.resolver.resolve(this.createOperand("form"), this.submission).size());
        assertEquals(0, this.resolver.resolve(this.createOperand("missing"), this.submission).size());
    }

    @Test
    void isEmptyWhenTheSchemaVersionIsOutOfReach()
    {
        this.context.resourceResolver().getResource(SUBMISSION_PATH).adaptTo(ModifiableValueMap.class)
            .put("schemaVersion", "gone");

        assertEquals(0, this.resolver.resolve(this.createOperand("is_proposal"), this.submission).size());
    }
}
