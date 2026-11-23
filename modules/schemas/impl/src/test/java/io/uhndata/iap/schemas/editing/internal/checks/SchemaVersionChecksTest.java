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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.uhndata.iap.documents.generation.api.GeneratedDocument;
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
    private static final String GENERIC = "application/octet-stream";

    private static final String LABEL = "label";

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private final List<ValidityCheck> checks = List.of(new QuestionBoundsCheck(), new QuestionPatternCheck(),
        new AnswerOptionsCheck(), new ConditionOperationsCheck(), new ConditionQuestionsCheck(),
        new GeneratedDocumentCheck(), new DocumentReferencesCheck());

    private SchemaFixture fixture;

    private Resource schema;

    private Resource draft;

    @BeforeEach
    void setUp() throws PersistenceException
    {
        this.fixture = new SchemaFixture(this.context);
        this.schema = this.fixture.schema("study");
        this.draft = this.fixture.version(this.schema, "v1", "draft");
    }

    @Test
    void applyToSchemaVersionsOnly()
    {
        assertTrue(this.checks.stream().allMatch(check -> check.appliesTo(this.draft)));
        assertTrue(this.checks.stream().noneMatch(check -> check.appliesTo(this.schema)));
    }

    @Test
    void letsADraftThatPassesTheChecksGoOn() throws PersistenceException
    {
        final Resource form = createPart(this.draft, "form", "sch:FormRequirement", Map.of(LABEL, "Form"));
        createPart(form, "age", "sch:Question", Map.of("text", "Age",
            "dataType", "long", "minAnswers", 1L, "maxAnswers", 1L, "minValue", 0.0d, "maxValue", 120.0d,
            "pattern", "[0-9]+"));

        assertEquals("", getProblems());
    }

    @Test
    void reportsEverythingThatStopsADraftFromBeingPublished() throws PersistenceException
    {
        final Resource form = createPart(this.draft, "form", "sch:FormRequirement", Map.of(LABEL, "Form"));
        final Resource counts = createPart(form, "counts", "sch:Question", Map.of(
            "text", "Counts", "minAnswers", 3L, "maxAnswers", 2L, "minValue", 5.0d, "maxValue", 1.0d,
            "pattern", "(unclosed", "optionsFrom", "/Vocabularies/counts"));
        createPart(counts, "a", "sch:AnswerOption", Map.of("value", "same"));
        createPart(counts, "b", "sch:AnswerOption", Map.of("value", "same"));
        createPart(counts, "c", "sch:AnswerOption", Map.of("value", " "));
        final Resource section = createPart(form, "section", "sch:Section", Map.of("title", "Details"));
        createCondition(section, "sometimes", Map.of("source", "answer", "value", new String[] { "form/gone" },
            "aggregate", "median"));
        final Resource outside = createPart(this.fixture.version(this.schema, "v0", "retired"), "q",
            "sch:Question", Map.of("text", "Elsewhere"));
        final String elsewhere = getIdentifier(outside);
        final Resource requirement = createPart(this.draft, "docs", "sch:DocumentRequirement",
            Map.of(LABEL, "Documents"));
        createCondition(requirement, "equals", Map.of("source", "answer", "value", new String[] { elsewhere }));
        final Resource approval = createPart(this.draft, "approval", "sch:ApprovalRequirement",
            Map.of(LABEL, "Approval"));
        createCondition(approval, "equals",
            Map.of("source", "answer", "value", new String[] { "00000000-0000-0000-0000-000000000000" }));

        final String message = getProblems();
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

    // A decision names a question just as an answer does, by path or by identifier
    @Test
    void acceptsConditionsOnQuestionsOfTheSameVersion() throws PersistenceException
    {
        final Resource form = createPart(this.draft, "form", "sch:FormRequirement", Map.of(LABEL, "Form"));
        final Resource question = createPart(form, "consent", "sch:Question", Map.of(
            "text", "Consent?", "dataType", "boolean", "maxAnswers", 0L, "minAnswers", 4L));
        final String uuid = getIdentifier(question);
        createCondition(createSection(form, "byPath"), "equals",
            Map.of("source", "answer", "value", new String[] { "form/consent" }, "aggregate", "count"));
        createCondition(createSection(form, "byUuid"), "is not empty",
            Map.of("source", "answer", "value", new String[] { uuid }));
        createCondition(createSection(form, "onTags"), "includes", Map.of("source", "tags"));
        createCondition(createSection(form, "decidedByPath"), "equals",
            Map.of("source", "decision", "value", new String[] { "form/consent" }));
        createCondition(createSection(form, "decidedByUuid"), "equals",
            Map.of("source", "decision", "value", new String[] { uuid }));

        assertEquals("", getProblems());
    }

    // Published, the upload would never be checked and the form never read, with nothing to say why
    @Test
    void reportsADocumentTheVersionLacks() throws PersistenceException
    {
        createPart(this.draft, "proposal", "sch:DocumentRequirement", Map.of(LABEL, "Proposal"));
        createPart(this.draft, "is_proposal", "sch:ValidationRequirement", Map.of(LABEL, "Is it a proposal",
            "prompt", "Decide whether it is a proposal.", "document", "proposal"));
        createPart(this.draft, "is_consent", "sch:ValidationRequirement", Map.of(LABEL, "Is it a consent",
            "prompt", "Decide whether it is a consent form.", "document", "consnet"));
        createPart(this.draft, "form", "sch:FormRequirement", Map.of(LABEL, "Form",
            "documents", new String[] { "proposal", "protocol" }));

        final String message = getProblems();
        assertTrue(message.contains("\"Is it a consent\" reads the document \"consnet\", which is not in this version"),
            message);
        assertTrue(message.contains("\"Form\" reads the document \"protocol\", which is not in this version"),
            message);
        assertFalse(message.contains("\"proposal\""), message);
    }

    // Never parsed, the document gives the step nothing to read, for every submission of the version
    @Test
    void reportsADocumentThatIsNeverParsed() throws PersistenceException
    {
        createPart(this.draft, "consent", "sch:DocumentRequirement", Map.of(LABEL, "Consent",
            "skipParsing", true));
        createPart(this.draft, "is_consent", "sch:ValidationRequirement", Map.of(LABEL, "Is it a consent",
            "prompt", "Decide whether it is a consent form.", "document", "consent"));

        assertEquals("\"Is it a consent\" reads the document \"consent\", which is never parsed", getProblems());
    }

    // The condition would never be true, so what it guards would never apply
    @Test
    void reportsAConditionOnAMisnamedValidation() throws PersistenceException
    {
        final Resource form = createPart(this.draft, "form", "sch:FormRequirement", Map.of(LABEL, "Form"));
        createCondition(form, "equals", Map.of("source", "validation", "value", new String[] { "is_propsal" }));

        assertEquals("\"Form\" has a condition on a validation that is not in this version", getProblems());
    }

    @Test
    void reportsAConditionOnARequirementThatIsNoValidation() throws PersistenceException
    {
        createPart(this.draft, "form", "sch:FormRequirement", Map.of(LABEL, "Form"));
        createCondition(createSection(this.draft, "category"), "equals",
            Map.of("source", "validation", "value", new String[] { "form" }));

        assertEquals("\"category\" has a condition on a validation that is not in this version", getProblems());
    }

    @Test
    void reportsAValidationConditionNamingNothing() throws PersistenceException
    {
        createCondition(createSection(this.draft, "unnamed"), "equals",
            Map.of("source", "validation", "value", new String[0]));

        assertEquals("\"unnamed\" has a condition on a validation that is not in this version", getProblems());
    }

    @Test
    void reportsADecisionOnAQuestionTheVersionLacks() throws PersistenceException
    {
        createCondition(createSection(this.draft, "routed"), "equals",
            Map.of("source", "decision", "value", new String[] { "form/gone" }));

        assertEquals("\"routed\" has a condition on a question that is not in this version", getProblems());
    }

    @Test
    void acceptsAConditionOnAValidationOfTheVersion() throws PersistenceException
    {
        createPart(this.draft, "proposal", "sch:DocumentRequirement", Map.of(LABEL, "Proposal"));
        createPart(this.draft, "is_proposal", "sch:ValidationRequirement", Map.of(LABEL, "Is it a proposal",
            "prompt", "Decide whether it is a proposal.", "document", "proposal"));
        final Resource form = createPart(this.draft, "form", "sch:FormRequirement", Map.of(LABEL, "Form"));
        createCondition(form, "equals", Map.of("source", "validation", "value", new String[] { "is_proposal" }));

        assertEquals("", getProblems());
    }

    @Test
    void namesAnUntitledItemByItsPath() throws PersistenceException
    {
        final Resource form = createPart(this.draft, "form", "sch:FormRequirement", Map.of(LABEL, "Form"));
        final Resource section = createPart(form, "nameless", "sch:Section", Map.of("title", " "));
        createCondition(section, "equals", Map.of("source", "answer", "value", new String[0]));

        final String message = getProblems();
        assertTrue(message.contains("form/nameless has a condition on a question"), message);
        assertFalse(message.contains("comparing"));
    }

    @Test
    void aGeneratedDocumentNeedsATemplate() throws PersistenceException
    {
        createPart(this.draft, "letter", "sch:DocumentRequirement",
            Map.of(LABEL, "Decision letter", "generated", true));

        assertEquals("\"Decision letter\" is generated, but has no template", getProblems());
    }

    @Test
    void aGeneratedDocumentNeedsADocxTemplate() throws PersistenceException
    {
        final Resource letter = createPart(this.draft, "letter", "sch:DocumentRequirement",
            Map.of(LABEL, "Decision letter", "generated", true));
        template(letter, "application/pdf");

        assertEquals("\"Decision letter\" is generated, but its template is not a .docx file", getProblems());
    }

    @Test
    void aGeneratedDocumentWithADocxTemplateGoesOn() throws PersistenceException
    {
        final Resource letter = createPart(this.draft, "letter", "sch:DocumentRequirement",
            Map.of(LABEL, "Decision letter", "generated", true));
        template(letter, GeneratedDocument.DOCX_MIME_TYPE);

        assertEquals("", getProblems());
    }

    @Test
    void aTemplateOfNoKnownTypeIsTakenForWhatItHolds() throws PersistenceException, IOException
    {
        // As the bundle's content loader records a file named "template", having no extension to go by
        final Resource letter = createPart(this.draft, "letter", "sch:DocumentRequirement",
            Map.of(LABEL, "Decision letter", "generated", true));
        final Resource memo = createPart(this.draft, "memo", "sch:DocumentRequirement",
            Map.of(LABEL, "Memo", "generated", true));
        final Resource note = createPart(this.draft, "note", "sch:DocumentRequirement",
            Map.of(LABEL, "Note", "generated", true));
        final Resource cut = createPart(this.draft, "cut", "sch:DocumentRequirement",
            Map.of(LABEL, "Cut", "generated", true));
        template(letter, GENERIC, zip("[Content_Types].xml", "word/document.xml"));
        template(memo, GENERIC, zip("[Content_Types].xml", "xl/workbook.xml"));
        template(note, GENERIC, new byte[] { 1 });
        // A zip cut off part way through its first entry
        template(cut, GENERIC, Arrays.copyOf(zip("word/document.xml"), 40));

        assertEquals("\"Memo\" is generated, but its template is not a .docx file; "
            + "\"Note\" is generated, but its template is not a .docx file; "
            + "\"Cut\" is generated, but its template is not a .docx file", getProblems());
    }

    @Test
    void anUploadedDocumentNeedsNoTemplate() throws PersistenceException
    {
        createPart(this.draft, "consent", "sch:DocumentRequirement", Map.of(LABEL, "Consent"));

        assertEquals("", getProblems());
    }

    private void template(final Resource requirement, final String mimeType) throws PersistenceException
    {
        template(requirement, mimeType, new byte[] { 1 });
    }

    private void template(final Resource requirement, final String mimeType, final byte[] content)
        throws PersistenceException
    {
        final ResourceResolver resolver = this.context.resourceResolver();
        final Resource template = resolver.create(requirement, "template", Map.of("jcr:primaryType", "nt:file"));
        resolver.create(template, "jcr:content", Map.of("jcr:primaryType", "nt:resource",
            "jcr:mimeType", mimeType, "jcr:data", new ByteArrayInputStream(content)));
        resolver.commit();
    }

    // A zip holding the named entries, each with a little content
    private static byte[] zip(final String... entries) throws IOException
    {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (final String entry : entries) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write("<xml/>".repeat(20).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private String getProblems()
    {
        return String.join("; ", this.checks.stream().flatMap(check -> check.check(this.draft).stream()).toList());
    }

    private Resource createPart(final Resource parent, final String name, final String primaryType,
        final Map<String, Object> properties) throws PersistenceException
    {
        return this.fixture.create(parent.getPath(), name, primaryType, properties);
    }

    private Resource createSection(final Resource parent, final String title) throws PersistenceException
    {
        return createPart(parent, title, "sch:Section", Map.of("title", title));
    }

    private static String getIdentifier(final Resource node)
    {
        return node.getValueMap().get("jcr:uuid", String.class);
    }

    private void createCondition(final Resource on, final String comparator, final Map<String, Object> operandA)
        throws PersistenceException
    {
        final ResourceResolver resolver = this.context.resourceResolver();
        final Resource condition = resolver.create(on, "cond:condition",
            Map.of("jcr:primaryType", "cond:SingleCondition", "comparator", comparator));
        final Map<String, Object> operand = new HashMap<>(operandA);
        operand.put("jcr:primaryType", "cond:ConditionOperand");
        resolver.create(condition, "operandA", operand);
        resolver.commit();
    }
}
