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
package io.uhndata.iap.schemas.editing.internal.checks;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.schemas.editing.internal.SchemaFixture;
import io.uhndata.iap.workflows.spi.ValidityCheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the checks a schema version must pass to be published, run together on one draft as the
 * {@code checkValid} step runs them.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SchemaVersionChecksTest
{
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private final List<ValidityCheck> checks = List.of(new QuestionBoundsCheck(), new QuestionPatternCheck(),
        new AnswerOptionsCheck(), new ConditionOperationsCheck(), new ConditionQuestionsCheck(),
        new GeneratedDocumentCheck());

    private SchemaFixture fixture;

    private Resource schema;

    @BeforeEach
    void setUp() throws PersistenceException
    {
        this.fixture = new SchemaFixture(this.context);
        this.schema = this.fixture.schema("study");
    }

    @Test
    void applyToSchemaVersionsOnly() throws PersistenceException
    {
        final Resource draft = this.fixture.version(this.schema, "v1", "draft");

        assertTrue(this.checks.stream().allMatch(check -> check.appliesTo(draft)));
        assertTrue(this.checks.stream().noneMatch(check -> check.appliesTo(this.schema)));
    }

    @Test
    void letsADraftThatPassesTheChecksGoOn() throws PersistenceException
    {
        final Resource draft = this.fixture.version(this.schema, "v1", "draft");
        this.fixture.create(draft.getPath(), "form", "sch:FormRequirement", Map.of("label", "Form"));
        this.fixture.create(draft.getPath() + "/form", "age", "sch:Question", Map.of("text", "Age",
            "dataType", "long", "minAnswers", 1L, "maxAnswers", 1L, "minValue", 0.0d, "maxValue", 120.0d,
            "pattern", "[0-9]+"));

        assertEquals("", problems(draft));
    }

    @Test
    void reportsEverythingThatStopsADraftFromBeingPublished() throws PersistenceException
    {
        final Resource draft = this.fixture.version(this.schema, "v1", "draft");
        final ResourceResolver resolver = this.context.resourceResolver();
        final Resource form = resolver.create(draft, "form", Map.of("jcr:primaryType", "sch:FormRequirement",
            "label", "Form"));
        final Resource counts = resolver.create(form, "counts", Map.of("jcr:primaryType", "sch:Question",
            "text", "Counts", "minAnswers", 3L, "maxAnswers", 2L, "minValue", 5.0d, "maxValue", 1.0d,
            "pattern", "(unclosed", "optionsFrom", "/Vocabularies/counts"));
        resolver.create(counts, "a", Map.of("jcr:primaryType", "sch:AnswerOption", "value", "same"));
        resolver.create(counts, "b", Map.of("jcr:primaryType", "sch:AnswerOption", "value", "same"));
        resolver.create(counts, "c", Map.of("jcr:primaryType", "sch:AnswerOption", "value", " "));
        final Resource section = resolver.create(form, "section", Map.of("jcr:primaryType", "sch:Section",
            "title", "Details"));
        condition(resolver, section, "sometimes", Map.of("source", "answer", "value", new String[] { "form/gone" },
            "aggregate", "median"));
        final Resource outside = resolver.create(this.fixture.version(this.schema, "v0", "retired"), "q",
            Map.of("jcr:primaryType", "sch:Question", "text", "Elsewhere"));
        resolver.commit();
        final String elsewhere = outside.getValueMap().get("jcr:uuid", String.class);
        final Resource requirement = resolver.create(draft, "docs", Map.of("jcr:primaryType",
            "sch:DocumentRequirement", "label", "Documents"));
        condition(resolver, requirement, "equals", Map.of("source", "answer", "value", new String[] { elsewhere }));
        final Resource approval = resolver.create(draft, "approval", Map.of("jcr:primaryType",
            "sch:ApprovalRequirement", "label", "Approval"));
        condition(resolver, approval, "equals",
            Map.of("source", "answer", "value", new String[] { "00000000-0000-0000-0000-000000000000" }));
        resolver.commit();

        final String message = problems(draft);
        assertTrue(message.contains("\"Counts\" asks for at least 3 answers but allows at most 2"), message);
        assertTrue(message.contains("\"Counts\" has a smallest accepted value above its largest"), message);
        assertTrue(message.contains("\"Counts\" has a pattern that is not a valid regular expression"), message);
        assertTrue(message.contains("\"Counts\" offers the value \"same\" more than once"), message);
        assertTrue(message.contains("\"Counts\" both lists its options and takes them from /Vocabularies/counts"),
            message);
        assertTrue(message.contains("\"Counts\" has an option without a value"), message);
        assertTrue(message.contains("\"Details\" has a condition comparing with \"sometimes\""), message);
        assertTrue(message.contains("\"Details\" has a condition aggregating with \"median\""), message);
        assertTrue(message.contains("\"Details\" has a condition on a question that is not in this version"),
            message);
        assertTrue(message.contains("\"Documents\" has a condition on a question"), message);
        assertTrue(message.contains("\"Approval\" has a condition on a question"), message);
    }

    @Test
    void acceptsConditionsOnQuestionsOfTheSameVersion() throws PersistenceException
    {
        final Resource draft = this.fixture.version(this.schema, "v1", "draft");
        final ResourceResolver resolver = this.context.resourceResolver();
        final Resource form = resolver.create(draft, "form", Map.of("jcr:primaryType", "sch:FormRequirement",
            "label", "Form"));
        final Resource question = resolver.create(form, "consent", Map.of("jcr:primaryType", "sch:Question",
            "text", "Consent?", "dataType", "boolean", "maxAnswers", 0L, "minAnswers", 4L));
        resolver.commit();
        final String uuid = question.getValueMap().get("jcr:uuid", String.class);
        final Resource byPath = resolver.create(form, "byPath", Map.of("jcr:primaryType", "sch:Section",
            "title", "By path"));
        condition(resolver, byPath, "equals", Map.of("source", "answer", "value", new String[] { "form/consent" },
            "aggregate", "count"));
        final Resource byUuid = resolver.create(form, "byUuid", Map.of("jcr:primaryType", "sch:Section",
            "title", "By identifier"));
        condition(resolver, byUuid, "is not empty", Map.of("source", "answer", "value", new String[] { uuid }));
        final Resource onTags = resolver.create(form, "onTags", Map.of("jcr:primaryType", "sch:Section",
            "title", "Tags"));
        condition(resolver, onTags, "includes", Map.of("source", "tags"));
        resolver.commit();

        assertEquals("", problems(draft));
    }

    @Test
    void namesAnUntitledItemByItsPath() throws PersistenceException
    {
        final Resource draft = this.fixture.version(this.schema, "v1", "draft");
        final ResourceResolver resolver = this.context.resourceResolver();
        final Resource form = resolver.create(draft, "form", Map.of("jcr:primaryType", "sch:FormRequirement",
            "label", "Form"));
        final Resource section = resolver.create(form, "nameless", Map.of("jcr:primaryType", "sch:Section",
            "title", " "));
        condition(resolver, section, "equals", Map.of("source", "answer", "value", new String[0]));
        resolver.commit();

        final String message = problems(draft);
        assertTrue(message.contains("form/nameless has a condition on a question"), message);
        assertFalse(message.contains("comparing"));
    }

    @Test
    void aGeneratedDocumentNeedsATemplate() throws PersistenceException
    {
        final Resource draft = this.fixture.version(this.schema, "v1", "draft");
        this.fixture.create(draft.getPath(), "letter", "sch:DocumentRequirement",
            Map.of("label", "Decision letter", "generated", true));

        assertEquals("\"Decision letter\" is generated, but has no template", problems(draft));
    }

    @Test
    void aGeneratedDocumentNeedsADocxTemplate() throws PersistenceException
    {
        final Resource draft = this.fixture.version(this.schema, "v1", "draft");
        final Resource letter = this.fixture.create(draft.getPath(), "letter", "sch:DocumentRequirement",
            Map.of("label", "Decision letter", "generated", true));
        template(letter, "application/pdf");

        assertEquals("\"Decision letter\" is generated, but its template is not a .docx file", problems(draft));
    }

    @Test
    void aGeneratedDocumentWithADocxTemplateGoesOn() throws PersistenceException
    {
        final Resource draft = this.fixture.version(this.schema, "v1", "draft");
        final Resource letter = this.fixture.create(draft.getPath(), "letter", "sch:DocumentRequirement",
            Map.of("label", "Decision letter", "generated", true));
        template(letter, GeneratedDocumentCheck.DOCX_MIME_TYPE);

        assertEquals("", problems(draft));
    }

    @Test
    void anUploadedDocumentNeedsNoTemplate() throws PersistenceException
    {
        final Resource draft = this.fixture.version(this.schema, "v1", "draft");
        this.fixture.create(draft.getPath(), "consent", "sch:DocumentRequirement", Map.of("label", "Consent"));

        assertEquals("", problems(draft));
    }

    private void template(final Resource requirement, final String mimeType) throws PersistenceException
    {
        final ResourceResolver resolver = this.context.resourceResolver();
        final Resource template = resolver.create(requirement, "template", Map.of("jcr:primaryType", "nt:file"));
        resolver.create(template, "jcr:content", Map.of("jcr:primaryType", "nt:resource",
            "jcr:mimeType", mimeType, "jcr:data", new ByteArrayInputStream(new byte[] { 1 })));
        resolver.commit();
    }

    private String problems(final Resource version)
    {
        return String.join("; ", this.checks.stream().flatMap(check -> check.check(version).stream()).toList());
    }

    private static void condition(final ResourceResolver resolver, final Resource on, final String comparator,
        final Map<String, Object> operandA) throws PersistenceException
    {
        final Resource condition = resolver.create(on, "cond:condition",
            Map.of("jcr:primaryType", "cond:SingleCondition", "comparator", comparator));
        final Map<String, Object> operand = new HashMap<>(operandA);
        operand.put("jcr:primaryType", "cond:ConditionOperand");
        resolver.create(condition, "operandA", operand);
    }
}
