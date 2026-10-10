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
package io.uhndata.iap.schemas.editing.internal;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.deletion.spi.DeletionMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ConditionDependencyVeto}: a question stays while a condition outside the deletion names it,
 * and the refusal names the parts holding those conditions.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class ConditionDependencyVetoTest
{
    private static final String QUESTION = "sch:Question";

    private static final String REFUSAL =
        "The conditions of \"Do you consent?\" depend on it. Change those conditions first.";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private final ConditionDependencyVeto veto = new ConditionDependencyVeto();

    private Session session;

    private Node version;

    private Node form;

    private Node age;

    @BeforeEach
    void setUp() throws PersistenceException, RepositoryException
    {
        final SchemaFixture fixture = new SchemaFixture(this.context);
        this.session = this.context.resourceResolver().adaptTo(Session.class);
        this.version = fixture.version(fixture.schema("study"), "v1", "draft").adaptTo(Node.class);
        this.form = this.version.addNode("form", "sch:FormRequirement");
        this.form.setProperty("label", "Participants");
        this.age = question(this.form, "age", "How old are you?");
        question(this.form, "unrelated", "Anything else?");
        dependsOn(question(this.form, "consent", "Do you consent?"), this.age);
        this.session.save();
    }

    @Test
    void isOneJudgementOfTheWholeDeletion()
    {
        assertEquals("Conditions depending on it", this.veto.getName());
        assertTrue(this.veto.judgesWholeOperation());
    }

    @Test
    void keepsAQuestionAnotherPartDependsOn() throws RepositoryException
    {
        assertEquals(REFUSAL, this.veto.veto(this.age, DeletionMode.ARCHIVE, this.session));
        assertEquals(REFUSAL, this.veto.veto(this.age, DeletionMode.PERMANENT, this.session));
    }

    @Test
    void namesEveryPartThatDependsOnIt() throws RepositoryException
    {
        final Node consentForm = this.version.addNode("consentForm", "sch:DocumentRequirement");
        consentForm.setProperty("label", "Consent form");
        dependsOn(consentForm, this.age);
        dependsOn(this.version.addNode("loose", "nt:unstructured"), this.age);
        this.session.save();

        assertEquals("The conditions of \"Consent form\", \"Do you consent?\", loose depend on it. Change those"
            + " conditions first.", this.veto.veto(this.age, DeletionMode.ARCHIVE, this.session));
    }

    @Test
    void keepsAQuestionAConditionNamesByPath() throws RepositoryException
    {
        final Node name = question(this.form, "name", "What is your name?");
        final Node nickname = question(this.form, "nickname", "What do people call you?");
        dependsOn(nickname, "form/name");
        this.session.save();

        assertEquals("The conditions of \"What do people call you?\" depend on it. Change those conditions first.",
            this.veto.veto(name, DeletionMode.ARCHIVE, this.session));
    }

    @Test
    void letsGoWhatTheDependentConditionsGoWith() throws RepositoryException
    {
        assertNull(this.veto.veto(this.form, DeletionMode.ARCHIVE, this.session));
        assertNull(this.veto.veto(this.version, DeletionMode.ARCHIVE, this.session));
    }

    @Test
    void letsGoWhatNothingDependsOn() throws RepositoryException
    {
        assertNull(this.veto.veto(this.form.getNode("unrelated"), DeletionMode.ARCHIVE, this.session));
        assertNull(this.veto.veto(this.form.getNode("consent"), DeletionMode.ARCHIVE, this.session));
        final Node option = this.age.addNode("adult", "sch:AnswerOption");
        option.setProperty("value", "adult");
        this.session.save();
        assertNull(this.veto.veto(option, DeletionMode.ARCHIVE, this.session));
    }

    @Test
    void judgesNeitherPurgesNorQuestionsOutsideAnyEntity() throws RepositoryException
    {
        assertNull(this.veto.veto(this.age, DeletionMode.PURGE, this.session));
        final Node stray = question(this.session.getRootNode(), "stray", "Where do I belong?");
        this.session.save();
        assertNull(this.veto.veto(stray, DeletionMode.ARCHIVE, this.session));
    }

    private static Node question(final Node parent, final String name, final String text) throws RepositoryException
    {
        final Node question = parent.addNode(name, QUESTION);
        question.setProperty("text", text);
        return question;
    }

    private static void dependsOn(final Node part, final Node question) throws RepositoryException
    {
        dependsOn(part, question.getIdentifier());
    }

    private static void dependsOn(final Node part, final String question) throws RepositoryException
    {
        if (!part.isNodeType("cond:Conditionable")) {
            part.addMixin("cond:Conditionable");
        }
        final Node condition = part.addNode("cond:condition", "cond:SingleCondition");
        condition.setProperty("comparator", "is not empty");
        final Node operand = condition.addNode("operandA", "cond:ConditionOperand");
        operand.setProperty("source", "answer");
        operand.setProperty("value", new String[] { question });
    }
}
