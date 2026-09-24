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

import java.io.IOException;
import java.io.StringReader;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.stream.Collectors;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.schemas.models.AnswerOption;
import io.uhndata.iap.schemas.models.ApprovalRequirement;
import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.schemas.models.Section;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Document;
import io.uhndata.iap.submissions.models.DocumentVersion;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Review;
import io.uhndata.iap.submissions.models.Submission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SubmissionFormServlet}: what a submitter is shown, what is left out because it does not
 * currently apply, and how a question says where its answer should be posted.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SubmissionFormServletTest
{
    private static final String TYPE = "sling:resourceType";

    /**
     * The supertype a real repository autocreates from the node type, and a mock repository does not. It is what
     * `getChildren(Requirement.RESOURCE_TYPE, ...)` selects on — the models reach the concrete kinds through their
     * abstract base — so without it a schema version reports no requirements at all.
     */
    private static final String SUPER_TYPE = "sling:resourceSuperType";

    private static final String REQUIREMENT = "sch/Requirement";

    private static final String APPROVAL = "approval";

    private static final String APPROVERS = "time-off-approvers";

    /** The review whose fixture carries a creation date, so that the date's spelling can be asserted. */
    private static final String DECIDED_AT = "dated";

    private static final String FORM_ITEM = "sch/FormItem";

    private static final String VERSION_PATH = "/Schemas/timeOffRequest/v1";

    private static final String SUBMISSION_PATH = "/Submissions/ab/cd/ef/aRequest";

    private static final String REQUESTER = "demo-requester";

    private static final String DETAILS = "details";

    private static final String START_DATE = "details/startDate";

    private static final String DURATION = "details/duration";

    private static final String END_DATE = "details/endDate";

    /** The schema parts these tests hide, by name; everything else applies. */
    private final Set<String> hidden = new HashSet<>();

    // JCR-backed: the submission points at its schema version with a real REFERENCE
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final SubmissionFormServlet servlet = new SubmissionFormServlet();

    @BeforeEach
    void setUp() throws Exception
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, Schema.class,
            SchemaVersion.class, FormRequirement.class, DocumentRequirement.class, ApprovalRequirement.class,
            Section.class, Question.class, AnswerOption.class, Answer.class, Document.class,
            DocumentVersion.class, File.class, Review.class, Submission.class);
        // Whether a request may still be answered is read from its lifecycle tag, which needs the view the
        // tags bundle provides
        Tagging.enable(this.context);
        // Everything applies unless a test says otherwise; the evaluator itself is exercised by its own module's
        // tests, and what matters here is that this servlet asks it about every part and honors the answer
        final ConditionEvaluator evaluator = Mockito.mock(ConditionEvaluator.class);
        Mockito.when(evaluator.applies(Mockito.any(), Mockito.any()))
            .thenAnswer(call -> !this.hidden.contains(((Content) call.getArgument(0)).getName()));
        inject(this.servlet, evaluator);

        this.context.create().resource("/Schemas/timeOffRequest", Map.of(
            TYPE, Schema.RESOURCE_TYPE, "title", "Time off request", "active", true));
        this.context.create().resource(VERSION_PATH, Map.of(
            TYPE, SchemaVersion.RESOURCE_TYPE, "version", "1.0", "active", true));
        this.context.create().resource(VERSION_PATH + "/" + DETAILS, Map.of(
            TYPE, FormRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT,
            "label", "Request details", "description", "When and why."));
        this.context.create().resource(VERSION_PATH + "/" + START_DATE, Map.of(
            TYPE, Question.RESOURCE_TYPE, SUPER_TYPE, FORM_ITEM,
            "text", "Which day does your time off start?", "dataType", "date", "minAnswers", 1L));
        this.context.create().resource(VERSION_PATH + "/" + END_DATE, Map.of(
            TYPE, Question.RESOURCE_TYPE, SUPER_TYPE, FORM_ITEM,
            "text", "Which day are you back?", "dataType", "date"));
        this.context.create().resource(VERSION_PATH + "/" + DURATION, Map.of(
            TYPE, Question.RESOURCE_TYPE, SUPER_TYPE, FORM_ITEM, "text", "How long?", "dataType", "text"));
        this.context.create().resource(VERSION_PATH + "/" + DURATION + "/half", Map.of(
            TYPE, AnswerOption.RESOURCE_TYPE, "value", "half-day", "label", "Half day",
            "description", "Morning or afternoon."));
        this.context.create().resource(VERSION_PATH + "/" + DURATION + "/several", Map.of(
            TYPE, AnswerOption.RESOURCE_TYPE, "value", "multiple-days"));
        this.context.create().resource(VERSION_PATH + "/doctorsNote", Map.of(
            TYPE, DocumentRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Doctor's note"));
        // The same kind of requirement, but saying what it takes and offering a blank to start from
        this.context.create().resource(VERSION_PATH + "/signedForm", Map.of(
            TYPE, DocumentRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Signed form",
            "acceptedFileTypes", new String[] {"application/pdf", "image/png"}));
        this.context.create().resource(VERSION_PATH + "/signedForm/template", Map.of(
            "jcr:primaryType", "nt:file"));
        this.context.create().resource(VERSION_PATH + "/" + APPROVAL, Map.of(
            TYPE, ApprovalRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Approval",
            "approverGroup", APPROVERS));

        this.context.create().resource(SUBMISSION_PATH, Map.of(
            TYPE, Submission.RESOURCE_TYPE, "title", "A long weekend", "createdBy", REQUESTER,
            "tags", new String[] {"draft"}));
        reference(SUBMISSION_PATH, VERSION_PATH, "schemaVersion");
    }

    // A question the submitter answered themselves has no block, which is what "nobody suggested this"
    // looks like on the wire
    @Test
    void saysNothingAboutWhereAnAnswerCameFromWhenNobodySuggestedIt() throws IOException
    {
        answer(START_DATE, "2026-10-06");

        assertFalse(item(requirement(form(REQUESTER), DETAILS), "startDate").containsKey("provenance"));
    }

    @Test
    void saysWhereAPreFilledAnswerCameFrom() throws IOException
    {
        extracted(START_DATE, "2026-10-06", 0.85);

        final JsonObject where =
            item(requirement(form(REQUESTER), DETAILS), "startDate").getJsonObject("provenance");

        assertEquals(List.of("2026-10-06"), where.getJsonArray("suggested").stream()
            .map(value -> ((JsonString) value).getString()).toList());
        assertEquals(0.85, where.getJsonNumber("confidence").doubleValue());
        assertEquals("Stated under Dates.", where.getString("reasoning"));
        assertFalse(where.getBoolean("reviewed"), "nobody has settled it yet");
        assertFalse(where.getBoolean("evidenceRejected"));
    }

    @Test
    void sendsTheQuoteAndWhereItLives() throws IOException
    {
        extracted(START_DATE, "2026-10-06", 0.85);

        final JsonObject passage =
            item(requirement(form(REQUESTER), DETAILS), "startDate").getJsonObject("provenance")
                .getJsonArray("passages").getJsonObject(0);

        assertEquals("leave begins on the sixth", passage.getString("quote"));
        assertEquals("p. 4 · 3.1 Dates", passage.getString("cite"));
    }

    // Step 2 keeps an extraction per attempt, so the form has to pick one
    @Test
    void showsTheSurestOfSeveralAttempts() throws IOException
    {
        final Resource answer = this.context.create().resource(SUBMISSION_PATH + "/a9", Map.of(
            TYPE, Answer.RESOURCE_TYPE, "value", new String[] {"2026-10-06"}));
        reference(answer.getPath(), VERSION_PATH + "/" + START_DATE, "question");
        // The surest one first, so that keeping it is a decision rather than the last one winning
        this.context.create().resource(answer.getPath() + "/e1", Map.of(
            TYPE, "sub/Extraction", "jcr:primaryType", "sub:Extraction",
            "extractedAnswer", "the better one", "confidence", 0.9));
        this.context.create().resource(answer.getPath() + "/e2", Map.of(
            TYPE, "sub/Extraction", "jcr:primaryType", "sub:Extraction",
            "extractedAnswer", "the first guess", "confidence", 0.3));

        final JsonObject where =
            item(requirement(form(REQUESTER), DETAILS), "startDate").getJsonObject("provenance");

        assertEquals(0.9, where.getJsonNumber("confidence").doubleValue());
        assertEquals("the better one", ((JsonString) where.getJsonArray("suggested").get(0)).getString());
    }

    // The whole point of recording the quote's page: a reader can open the document there and check it
    @Test
    void pointsEachPassageAtThePdfItWasReadFrom() throws IOException
    {
        final Resource extraction = extractionReadingTwoQuotes("a7");
        final String version = documentVersionWith("note", "pdfFile");
        references(extraction.getPath(), "sources", version);

        final JsonArray passages = item(requirement(form(REQUESTER), DETAILS), "startDate")
            .getJsonObject("provenance").getJsonArray("passages");

        assertEquals(version + "/file/pdfFile#page=4", passages.getJsonObject(0).getString("source"));
        assertEquals(version + "/file/pdfFile", passages.getJsonObject(1).getString("source"),
            "a quote the document gave no page for still opens the document");
    }

    // A run that read two documents as one text has two sources, and a quote that says which one it came from
    // opens that one rather than whichever the run lists first
    @Test
    void pointsAPassageAtTheDocumentItWasQuotedFrom() throws IOException
    {
        final Resource extraction = extractionReadingTwoQuotes("a8");
        final String preamble = documentVersionWith("preamble", "pdfFile");
        final String questionnaire = documentVersionWith("questionnaire", "pdfFile");
        references(extraction.getPath(), "sources", preamble, questionnaire);
        reference(extraction.getPath() + "/q1", questionnaire, "source");

        final JsonArray passages = item(requirement(form(REQUESTER), DETAILS), "startDate")
            .getJsonObject("provenance").getJsonArray("passages");

        assertEquals(questionnaire + "/file/pdfFile#page=4", passages.getJsonObject(0).getString("source"));
        assertEquals(preamble + "/file/pdfFile", passages.getJsonObject(1).getString("source"),
            "a quote naming no document of its own opens the run's first");
    }

    // A revision whose upload never landed, and one whose parse produced no PDF: neither can be opened,
    // and a link that led nowhere would read as though the document were one click away
    @Test
    void offersNothingToOpenWhenNoRevisionHasAPdf() throws IOException
    {
        final Resource extraction = extractionReadingTwoQuotes("a6");
        this.context.create().resource(SUBMISSION_PATH + "/gone/1", Map.of(TYPE, DocumentVersion.RESOURCE_TYPE));
        references(extraction.getPath(), "sources",
            SUBMISSION_PATH + "/gone/1", documentVersionWith("note", null));

        final JsonArray passages = item(requirement(form(REQUESTER), DETAILS), "startDate")
            .getJsonObject("provenance").getJsonArray("passages");

        assertFalse(passages.getJsonObject(0).containsKey("source"));
    }

    /** An extracted answer with two quotes behind it, one placed on a page and one placed nowhere. */
    private Resource extractionReadingTwoQuotes(final String name)
    {
        final Resource answer = this.context.create().resource(SUBMISSION_PATH + "/" + name, Map.of(
            TYPE, Answer.RESOURCE_TYPE, "value", new String[] {"2026-10-06"}));
        reference(answer.getPath(), VERSION_PATH + "/" + START_DATE, "question");
        final Resource extraction = this.context.create().resource(answer.getPath() + "/e1", Map.of(
            TYPE, "sub/Extraction", "jcr:primaryType", "sub:Extraction",
            "extractedAnswer", "2026-10-06", "confidence", 0.9));
        this.context.create().resource(extraction.getPath() + "/q1", Map.of(
            TYPE, "sub/Evidence", "jcr:primaryType", "sub:Evidence",
            "quote", "leave begins on the sixth", "page", 4L));
        this.context.create().resource(extraction.getPath() + "/q2", Map.of(
            TYPE, "sub/Evidence", "jcr:primaryType", "sub:Evidence", "quote", "and again here"));
        return extraction;
    }

    /** A document revision holding a file, with the named rendition under it when one is named. */
    private String documentVersionWith(final String document, final String rendition)
    {
        final String path = SUBMISSION_PATH + "/" + document + "/1";
        this.context.create().resource(path, Map.of(TYPE, DocumentVersion.RESOURCE_TYPE));
        this.context.create().resource(path + "/file", Map.of(TYPE, File.RESOURCE_TYPE));
        if (rendition != null) {
            this.context.create().resource(path + "/file/" + rendition, Map.of("jcr:primaryType", "nt:file"));
        }
        return path;
    }

    // A schema version that names no reading workflow never fills an answer in from a document, however
    // many are attached. The view needs the difference to tell a form waiting for its reading from one
    // that is simply unanswered.
    @Test
    void saysWhetherAnythingReadsTheAttachedDocuments() throws IOException
    {
        assertFalse(form(REQUESTER).getBoolean("readsDocuments"));

        modify(VERSION_PATH, "readingWorkflow", "the-reading-workflow");

        assertTrue(form(REQUESTER).getBoolean("readsDocuments"));
    }

    // A bare extraction: nothing found, nothing said, and no page markers in the source
    @Test
    void copesWithAnExtractionThatSaysAlmostNothing() throws IOException
    {
        final Resource answer = this.context.create().resource(SUBMISSION_PATH + "/a8", Map.of(
            TYPE, Answer.RESOURCE_TYPE, "value", new String[] {"2026-10-06"}));
        reference(answer.getPath(), VERSION_PATH + "/" + START_DATE, "question");
        final Resource extraction = this.context.create().resource(answer.getPath() + "/e1", Map.of(
            TYPE, "sub/Extraction", "jcr:primaryType", "sub:Extraction"));
        this.context.create().resource(extraction.getPath() + "/q1", Map.of(
            TYPE, "sub/Evidence", "jcr:primaryType", "sub:Evidence", "quote", "somewhere in here"));

        final JsonObject where =
            item(requirement(form(REQUESTER), DETAILS), "startDate").getJsonObject("provenance");

        assertEquals(0.0, where.getJsonNumber("confidence").doubleValue());
        assertTrue(where.getJsonArray("suggested").isEmpty());
        assertFalse(where.containsKey("reasoning"));
        assertFalse(where.getJsonArray("passages").getJsonObject(0).containsKey("cite"),
            "a DOCX carries no pages, and this passage has no heading either");
    }

    @Test
    void servesWhatTheSchemaAsksAndWhatIsAlreadyAnswered() throws IOException
    {
        answer(START_DATE, "2026-10-06");

        final JsonObject form = form(REQUESTER);

        assertEquals(SUBMISSION_PATH, form.getString("path"));
        assertEquals("A long weekend", form.getString("title"));
        final JsonObject details = requirement(form, DETAILS);
        assertEquals(FormRequirement.RESOURCE_TYPE, details.getString("type"));
        assertEquals("Request details", details.getString("label"));
        final JsonObject startDate = item(details, "startDate");
        assertEquals("Which day does your time off start?", startDate.getString("text"));
        assertEquals("date", startDate.getString("dataType"));
        // The pair as stored, not derived flags; an absent maximum reads as the single value it defaults to
        assertEquals(1, startDate.getInt("minAnswers"));
        assertEquals(1, startDate.getInt("maxAnswers"));
        // The value constraints are stated only where the schema states them
        assertFalse(startDate.containsKey("minValue"));
        assertFalse(startDate.containsKey("maxValue"));
        assertFalse(startDate.containsKey("pattern"));
        assertFalse(startDate.containsKey("patternMessage"));
        // Stated even when there is nothing to offer, so that "answered freely" is something the form says
        assertTrue(startDate.getJsonArray("options").isEmpty());

        // A question offering answers carries them, in the order the schema declares them, with the
        // label falling back to the value so an option may declare only the one string
        final JsonArray offered = item(details, "duration").getJsonArray("options");
        assertEquals(2, offered.size());
        assertEquals("half-day", offered.getJsonObject(0).getString("value"));
        assertEquals("Half day", offered.getJsonObject(0).getString("label"));
        assertEquals("multiple-days", offered.getJsonObject(1).getString("value"));
        assertEquals("multiple-days", offered.getJsonObject(1).getString("label"));
        assertEquals("Morning or afternoon.", offered.getJsonObject(0).getString("description"));
        assertFalse(offered.getJsonObject(1).containsKey("description"));
        assertEquals("2026-10-06", startDate.getJsonArray("value").getString(0));
        // Unanswered, and saying so as an empty list rather than by omission
        assertTrue(item(details, "endDate").getJsonArray("value").isEmpty());
    }

    @Test
    void statesTheConstraintsTheSchemaStates() throws IOException
    {
        this.context.create().resource(VERSION_PATH + "/" + DETAILS + "/daysAway", Map.of(
            TYPE, Question.RESOURCE_TYPE, SUPER_TYPE, FORM_ITEM, "text", "How many days?", "dataType", "long",
            "maxAnswers", 3L, "minValue", 0.5d, "maxValue", 30.0d,
            "pattern", "[0-9.]+", "patternMessage", "A number of days."));

        final JsonObject question = item(requirement(form(REQUESTER), DETAILS), "daysAway");

        assertEquals(0, question.getInt("minAnswers"));
        assertEquals(3, question.getInt("maxAnswers"));
        assertEquals(0.5d, question.getJsonNumber("minValue").doubleValue());
        assertEquals(30.0d, question.getJsonNumber("maxValue").doubleValue());
        assertEquals("[0-9.]+", question.getString("pattern"));
        assertEquals("A number of days.", question.getString("patternMessage"));
    }

    @Test
    void saysWhetherEachDocumentIsDemandedOrMerelyOffered() throws IOException
    {
        this.context.create().resource(VERSION_PATH + "/sponsorLetter", Map.of(
            TYPE, DocumentRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Sponsor letter",
            "required", false));

        final JsonObject form = form(REQUESTER);

        // Absence of the flag on the node means demanded, and the wire says so explicitly
        assertTrue(requirement(form, "doctorsNote").getBoolean("required"));
        assertFalse(requirement(form, "sponsorLetter").getBoolean("required"));
        // Only document requirements carry the key; a form's optionality lives per-question in minAnswers
        assertFalse(requirement(form, DETAILS).containsKey("required"));
    }

    @Test
    void tellsEachQuestionWhereItsAnswerGoes() throws IOException
    {
        // The path the save endpoint expects, relative to the schema version. Given rather than constructed, so
        // that only one side of the exchange decides how a question is addressed.
        assertEquals(START_DATE, item(requirement(form(REQUESTER), DETAILS), "startDate").getString("path"));
    }

    @Test
    void leavesOutAQuestionThatDoesNotApply() throws IOException
    {
        // The demo's own shape: the return date is asked only when the absence covers several days
        this.hidden.add("endDate");

        final JsonArray items = requirement(form(REQUESTER), DETAILS).getJsonArray("items");

        assertEquals(Set.of("startDate", "duration"), names(items));
    }

    @Test
    void leavesOutAWholeRequirementThatDoesNotApply() throws IOException
    {
        this.hidden.add("doctorsNote");
        this.hidden.add("signedForm");
        this.hidden.add(APPROVAL);

        assertEquals(Set.of(DETAILS), names(form(REQUESTER).getJsonArray("requirements")));
    }

    @Test
    void describesRequirementsThatHoldNoQuestions() throws IOException
    {
        // A document requirement has no items, and must still be described: it is something the submitter has to
        // do, and an editor that only rendered questions would silently drop it
        final JsonObject note = requirement(form(REQUESTER), "doctorsNote");

        assertEquals(DocumentRequirement.RESOURCE_TYPE, note.getString("type"));
        assertEquals("Doctor's note", note.getString("label"));
        assertFalse(note.containsKey("items"));
        // Restricted to nothing in particular, said as an empty list rather than by omission: a reader has to be
        // able to tell "takes anything" from "the server did not say"
        assertTrue(note.getJsonArray("acceptedFileTypes").isEmpty());
        assertFalse(note.containsKey("template"));
        assertTrue(note.getJsonArray("attached").isEmpty());
    }

    @Test
    void namesWhatHasAlreadyBeenAttachedForARequirement() throws IOException
    {
        // Named rather than counted, so that a form reopened later says which document is there: an upload control
        // that looks the same before and after leaves the only way to check outside the page
        final Resource document = this.context.create().resource(SUBMISSION_PATH + "/d1", Map.of(
            TYPE, Document.RESOURCE_TYPE, "title", "note.pdf"));
        reference(document.getPath(), VERSION_PATH + "/doctorsNote", "fulfills");

        assertEquals(List.of("note.pdf"), requirement(form(REQUESTER), "doctorsNote").getJsonArray("attached")
            .stream()
            .map(value -> ((JsonString) value).getString())
            .collect(Collectors.toList()));
    }

    @Test
    void fallsBackOnANameForAnUntitledAttachment() throws IOException
    {
        // A document created by something other than the attach workflow may carry no title at all, and a form
        // saying "Attached: " with nothing after it reads as broken rather than as untitled
        final Resource document = this.context.create().resource(SUBMISSION_PATH + "/d2", Map.of(
            TYPE, Document.RESOURCE_TYPE));
        reference(document.getPath(), VERSION_PATH + "/doctorsNote", "fulfills");

        assertEquals(List.of("d2"), requirement(form(REQUESTER), "doctorsNote").getJsonArray("attached").stream()
            .map(value -> ((JsonString) value).getString())
            .collect(Collectors.toList()));
    }

    @Test
    void leavesOutADocumentAttachedForSomeOtherRequirement() throws IOException
    {
        // The reference is what ties a document to a requirement, not being a child of the same submission
        final Resource document = this.context.create().resource(SUBMISSION_PATH + "/d3", Map.of(
            TYPE, Document.RESOURCE_TYPE, "title", "form.pdf"));
        reference(document.getPath(), VERSION_PATH + "/signedForm", "fulfills");

        assertTrue(requirement(form(REQUESTER), "doctorsNote").getJsonArray("attached").isEmpty());
        assertFalse(requirement(form(REQUESTER), "signedForm").getJsonArray("attached").isEmpty());
    }

    @Test
    void leavesOutADocumentThatFulfillsNothing() throws IOException
    {
        // A document with no reference at all: whatever it is, it is not the answer to this requirement
        this.context.create().resource(SUBMISSION_PATH + "/d4", Map.of(
            TYPE, Document.RESOURCE_TYPE, "title", "stray.pdf"));

        assertTrue(requirement(form(REQUESTER), "doctorsNote").getJsonArray("attached").isEmpty());
    }

    @Test
    void saysWhatADocumentRequirementTakesAndWhatItOffersToStartFrom() throws IOException
    {
        // Both are here because an upload control cannot be drawn without them, and this projection is the only
        // place that says which requirements currently apply
        final JsonObject signed = requirement(form(REQUESTER), "signedForm");

        assertEquals(List.of("application/pdf", "image/png"), signed.getJsonArray("acceptedFileTypes").stream()
            .map(value -> ((JsonString) value).getString())
            .collect(Collectors.toList()));
        assertEquals(VERSION_PATH + "/signedForm/template", signed.getString("template"));
    }

    @Test
    void describesNestedSections() throws IOException
    {
        this.context.create().resource(VERSION_PATH + "/" + DETAILS + "/when", Map.of(
            TYPE, Section.RESOURCE_TYPE, SUPER_TYPE, FORM_ITEM, "title", "Dates"));
        this.context.create().resource(VERSION_PATH + "/" + DETAILS + "/when/returning", Map.of(
            TYPE, Question.RESOURCE_TYPE, SUPER_TYPE, FORM_ITEM, "text", "Returning?", "dataType", "date"));

        final JsonObject section = item(requirement(form(REQUESTER), DETAILS), "when");

        assertEquals(Section.RESOURCE_TYPE, section.getString("type"));
        assertEquals("Dates", section.getString("label"));
        // A question inside a section is addressed through it, so the path is built from the whole descent
        assertEquals("details/when/returning", item(section, "returning").getString("path"));
    }

    @Test
    void reportsEditableToTheSubmitterOfADraft() throws IOException
    {
        assertTrue(form(REQUESTER).getBoolean("editable"));
    }

    @Test
    void reportsNotEditableToAnybodyElse() throws IOException
    {
        // The same two rules the save handler enforces, so an editor offers editing only where a save would be
        // accepted rather than finding out from a refusal
        assertFalse(form("demo-approver").getBoolean("editable"));
    }

    @Test
    void reportsNotEditableOnceItIsNoLongerADraft() throws IOException
    {
        modify(SUBMISSION_PATH, "tags", new String[] {"submitted"});

        assertFalse(form(REQUESTER).getBoolean("editable"));
    }

    // What the answer is for, in the schema author's words. A submitter looking at a question they did not
    // expect wants to know why it is being asked, which is not what the description tells them.
    @Test
    void carriesWhyAQuestionIsAsked() throws IOException
    {
        assertFalse(item(requirement(form(REQUESTER), DETAILS), "startDate").containsKey("purpose"),
            "a question that states no purpose claims none");

        modify(VERSION_PATH + "/" + START_DATE, "purpose", "Working out who covers the desk");

        assertEquals("Working out who covers the desk",
            item(requirement(form(REQUESTER), DETAILS), "startDate").getString("purpose"));
    }

    @Test
    void saysNothingAboutExtractionBeforeItWasAskedFor() throws IOException
    {
        assertFalse(form(REQUESTER).containsKey("extraction"));
    }

    @Test
    void saysWhereReadingTheDocumentsGotTo() throws IOException
    {
        modify(SUBMISSION_PATH, "extractionStatus", "failed");
        modify(SUBMISSION_PATH, "extractionMessage", "The model's answer could not be read");

        final JsonObject extraction = form(REQUESTER).getJsonObject("extraction");

        assertEquals("failed", extraction.getString("status"));
        assertEquals("The model's answer could not be read", extraction.getString("message"));
    }

    // Whether the view offers to try again. A reading that failed for its own reasons is not retryable:
    // sending the same document to the daemon a second time changes nothing about the model refusing it.
    @Test
    void saysWhetherAskingAgainWouldDoAnything() throws IOException
    {
        modify(SUBMISSION_PATH, "extractionStatus", "failed");
        this.context.create().resource(SUBMISSION_PATH + "/d5", Map.of(TYPE, Document.RESOURCE_TYPE));

        assertFalse(form(REQUESTER).getJsonObject("extraction").getBoolean("retryable"),
            "a document with nothing uploaded has no parse to have failed");

        this.context.create().resource(SUBMISSION_PATH + "/d6", Map.of(
            TYPE, Document.RESOURCE_TYPE, "title", "note.pdf"));
        final String version = documentVersionWith("d6", null);
        modify(version + "/file", "parseStatus", "completed");

        assertFalse(form(REQUESTER).getJsonObject("extraction").getBoolean("retryable"),
            "a document that was read has nothing to send again");

        modify(version + "/file", "parseStatus", "failed");

        assertTrue(form(REQUESTER).getJsonObject("extraction").getBoolean("retryable"));
    }

    @Test
    void leavesOutWhatTheReadingHasNotSaidYet() throws IOException
    {
        modify(SUBMISSION_PATH, "extractionStatus", "running");

        final JsonObject extraction = form(REQUESTER).getJsonObject("extraction");

        assertEquals("running", extraction.getString("status"));
        assertFalse(extraction.containsKey("message"));
        assertFalse(extraction.containsKey("category"));
    }

    @Test
    void passesOverAnAnswerWhoseQuestionIsGone() throws IOException
    {
        // A question removed from the schema leaves its answer behind; it is the answer to nothing being asked
        this.context.create().resource(SUBMISSION_PATH + "/orphan", Map.of(
            TYPE, Answer.RESOURCE_TYPE, "value", new String[] {"stale"}));

        assertTrue(item(requirement(form(REQUESTER), DETAILS), "startDate").getJsonArray("value").isEmpty());
    }

    private JsonObject form(final String reader) throws IOException
    {
        this.context.resourceResolver().refresh();
        final Resource submission =
            Objects.requireNonNull(this.context.resourceResolver().getResource(SUBMISSION_PATH));
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.servlet.doGet(request(submission, reader), response);

        assertEquals(MockSlingJakartaHttpServletResponse.SC_OK, response.getStatus());
        try (var reading = Json.createReader(new StringReader(response.getOutputAsString()))) {
            return reading.readObject();
        }
    }

    /**
     * A request from a named person. The identity has to come from the repository's side of the session: a login
     * resolves case-insensitively, so Sling reports the spelling that was typed while the repository reports the
     * one it resolved it to, and only the second is an identity. The two are deliberately made to disagree here,
     * so that a servlet reading the wrong one fails these tests rather than passing them by coincidence.
     */
    private MockSlingJakartaHttpServletRequest request(final Resource resource, final String reader)
    {
        final Session masked = Mockito.mock(Session.class,
            AdditionalAnswers.delegatesTo(this.context.resourceResolver().adaptTo(Session.class)));
        Mockito.doReturn(reader).when(masked).getUserID();
        final ResourceResolver resolver = new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public String getUserID()
            {
                return reader.toUpperCase(Locale.ROOT);
            }

            @Override
            public <T> T adaptTo(final Class<T> type)
            {
                return type == Session.class ? type.cast(masked) : super.adaptTo(type);
            }
        };
        final MockSlingJakartaHttpServletRequest request =
            new MockSlingJakartaHttpServletRequest(resolver, this.context.bundleContext());
        request.setResource(resource);
        return request;
    }

    private static JsonObject requirement(final JsonObject form, final String name)
    {
        return named(form.getJsonArray("requirements"), name);
    }

    private static JsonObject item(final JsonObject container, final String name)
    {
        return named(container.getJsonArray("items"), name);
    }

    private static JsonObject named(final JsonArray entries, final String name)
    {
        return entries.getValuesAs(JsonObject.class).stream()
            .filter(entry -> name.equals(entry.getString("name")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No " + name + " in " + entries));
    }

    private static Set<String> names(final JsonArray entries)
    {
        return entries.getValuesAs(JsonObject.class).stream()
            .map(entry -> entry.getString("name"))
            .collect(Collectors.toSet());
    }

    @Test
    void saysWhoAnApprovalWaitsOnWhileNobodyHasDecided() throws IOException
    {
        // Nobody fills an approval in here, so the honest thing to project is where it stands. Saying only that
        // it cannot be completed here reads the same as a part of the form that is broken
        final JsonObject approval = requirement(form(REQUESTER), APPROVAL);

        assertEquals(ApprovalRequirement.RESOURCE_TYPE, approval.getString("type"));
        assertEquals(APPROVERS, approval.getString("approverGroup"));
        assertFalse(approval.getBoolean("approved"));
        assertFalse(approval.containsKey("decidedBy"));
        assertFalse(approval.containsKey("decidedAt"));
    }

    @Test
    void reportsTheDecisionOnceSomebodyHasMadeIt() throws IOException
    {
        review(DECIDED_AT, "priya", true);

        final JsonObject approval = requirement(form(REQUESTER), APPROVAL);

        assertTrue(approval.getBoolean("approved"));
        assertEquals("priya", approval.getString("decidedBy"));
        // Spelled the way every other date in the JSON is, numeric offset and all, so a reader parses one format
        assertEquals("2026-08-27T09:15:30.500-05:00", approval.getString("decidedAt"));
    }

    @Test
    void reportsARefusalAsADecisionThatDidNotApprove() throws IOException
    {
        // A rejection is a review that is not approved, which is why who decided is reported whenever a review
        // exists rather than only when it granted the approval
        review("r1", "priya", false);

        final JsonObject approval = requirement(form(REQUESTER), APPROVAL);

        assertFalse(approval.getBoolean("approved"));
        assertEquals("priya", approval.getString("decidedBy"));
    }

    @Test
    void reportsTheLastWordWhenAnApprovalWasRevisited() throws IOException
    {
        review("r1", "priya", false);
        review("r2", "sam", true);

        final JsonObject approval = requirement(form(REQUESTER), APPROVAL);

        assertTrue(approval.getBoolean("approved"));
        assertEquals("sam", approval.getString("decidedBy"));
    }

    @Test
    void leavesOutAReviewOfSomethingElse() throws IOException
    {
        // A review naming another requirement, or naming none at all, is not this approval's decision
        final Resource elsewhere = this.context.create().resource(SUBMISSION_PATH + "/r1", Map.of(
            TYPE, Review.RESOURCE_TYPE, "reviewer", "priya", "tags", new String[] {"approved"}));
        reference(elsewhere.getPath(), VERSION_PATH + "/doctorsNote", "requirement");
        this.context.create().resource(SUBMISSION_PATH + "/r2", Map.of(
            TYPE, Review.RESOURCE_TYPE, "reviewer", "sam", "tags", new String[] {"approved"}));

        final JsonObject approval = requirement(form(REQUESTER), APPROVAL);

        assertFalse(approval.getBoolean("approved"));
        assertFalse(approval.containsKey("decidedBy"));
    }

    @Test
    void saysAnApprovalIsNotNarrowedToAGroupRatherThanOmittingIt() throws IOException
    {
        this.context.create().resource(VERSION_PATH + "/rebApproval", Map.of(
            TYPE, ApprovalRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "REB approval"));

        assertEquals("", requirement(form(REQUESTER), "rebApproval").getString("approverGroup"));
    }

    /**
     * Records one review against the standing approval requirement.
     *
     * @param name the node name to give it
     * @param reviewer who decided
     * @param approved whether they granted the approval
     */
    private void review(final String name, final String reviewer, final boolean approved)
    {
        final Map<String, Object> properties = new HashMap<>(Map.of(
            TYPE, Review.RESOURCE_TYPE, "reviewer", reviewer,
            "tags", approved ? new String[] {"approved"} : new String[0]));
        // A mock resource carries no jcr:created, which is also the case this projection has to survive: the date
        // is given only where a test is about it
        final Resource review = this.context.create().resource(SUBMISSION_PATH + "/" + name, properties);
        if (DECIDED_AT.equals(name)) {
            final Calendar decided = new GregorianCalendar(TimeZone.getTimeZone("GMT-05:00"));
            decided.clear();
            decided.set(2026, Calendar.AUGUST, 27, 9, 15, 30);
            decided.set(Calendar.MILLISECOND, 500);
            modify(review.getPath(), "jcr:created", decided);
        }
        reference(review.getPath(), VERSION_PATH + "/" + APPROVAL, "requirement");
    }

    private void answer(final String questionPath, final String value)
    {
        final Resource answer = this.context.create().resource(SUBMISSION_PATH + "/" + value.hashCode(), Map.of(
            TYPE, Answer.RESOURCE_TYPE, "value", new String[] {value}));
        reference(answer.getPath(), VERSION_PATH + "/" + questionPath, "question");
    }

    /** An answer a model read, with the extraction and the quote behind it. */
    private void extracted(final String questionPath, final String value, final double confidence)
    {
        final Resource answer = this.context.create().resource(SUBMISSION_PATH + "/" + value.hashCode(), Map.of(
            TYPE, Answer.RESOURCE_TYPE, "value", new String[] {value}));
        reference(answer.getPath(), VERSION_PATH + "/" + questionPath, "question");
        final Resource extraction = this.context.create().resource(answer.getPath() + "/e1", Map.of(
            TYPE, "sub/Extraction", "jcr:primaryType", "sub:Extraction",
            "extractedAnswer", value, "confidence", confidence,
            "reasoning", "Stated under Dates."));
        this.context.create().resource(extraction.getPath() + "/q1", Map.of(
            TYPE, "sub/Evidence", "jcr:primaryType", "sub:Evidence",
            "quote", "leave begins on the sixth", "header", "3.1 Dates", "page", 4L));
    }

    /** A multi-valued REFERENCE, which is how an extraction points at the revisions it read. */
    private void references(final String fromPath, final String property, final String... toPaths)
    {
        try {
            final Node source = Objects.requireNonNull(
                this.context.resourceResolver().getResource(fromPath)).adaptTo(Node.class);
            final Value[] values = new Value[toPaths.length];
            for (int i = 0; i < toPaths.length; i++) {
                values[i] = source.getSession().getValueFactory().createValue(Objects.requireNonNull(
                    this.context.resourceResolver().getResource(toPaths[i])).adaptTo(Node.class));
            }
            source.setProperty(property, values);
            this.context.resourceResolver().commit();
        } catch (final RepositoryException | PersistenceException e) {
            throw new IllegalStateException(e);
        }
    }

    private void reference(final String fromPath, final String toPath, final String property)
    {
        try {
            final Node source = Objects.requireNonNull(
                this.context.resourceResolver().getResource(fromPath)).adaptTo(Node.class);
            source.setProperty(property, Objects.requireNonNull(
                this.context.resourceResolver().getResource(toPath)).adaptTo(Node.class));
            this.context.resourceResolver().commit();
        } catch (final RepositoryException | PersistenceException e) {
            throw new IllegalStateException(e);
        }
    }

    private void modify(final String path, final String property, final Object value)
    {
        try {
            Objects.requireNonNull(this.context.resourceResolver().getResource(path))
                .adaptTo(ModifiableValueMap.class).put(property, value);
            this.context.resourceResolver().commit();
        } catch (final PersistenceException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void inject(final SubmissionFormServlet servlet, final ConditionEvaluator evaluator)
        throws ReflectiveOperationException
    {
        // The house idiom for a component under unit test: DS metadata only exists in the packaged bundle, so the
        // references are set by reflection rather than by registerInjectActivateService
        final var field = SubmissionFormServlet.class.getDeclaredField("conditions");
        field.setAccessible(true);
        field.set(servlet, evaluator);
    }
}
