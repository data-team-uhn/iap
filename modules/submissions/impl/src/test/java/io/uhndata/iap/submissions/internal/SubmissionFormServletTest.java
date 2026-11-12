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
import java.util.stream.Stream;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.commons.mime.MimeTypeService;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletRequest;
import org.apache.sling.testing.mock.sling.servlet.MockSlingJakartaHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.errortracking.api.ErrorLoggerService;
import io.uhndata.iap.schemas.models.AnswerOption;
import io.uhndata.iap.schemas.models.ApprovalRequirement;
import io.uhndata.iap.schemas.models.ClassificationRequirement;
import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.schemas.models.Section;
import io.uhndata.iap.schemas.models.ValidationRequirement;
import io.uhndata.iap.submissions.internal.describers.ApprovalRequirementDescriber;
import io.uhndata.iap.submissions.internal.describers.DocumentRequirementDescriber;
import io.uhndata.iap.submissions.internal.describers.FormRequirementDescriber;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Context;
import io.uhndata.iap.submissions.models.Document;
import io.uhndata.iap.submissions.models.DocumentVersion;
import io.uhndata.iap.submissions.models.Extraction;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Review;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.submissions.spi.RequirementDescriber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SubmissionFormServlet}: what a submitter is shown, what is left out because it does not apply
 * now, and where each answer is posted.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SubmissionFormServletTest
{
    private static final String TYPE = "sling:resourceType";

    /**
     * The supertype Oak autocreates from the node type, and a mock repository does not. A schema version lists its
     * requirements by it, so without it a version reports none.
     */
    private static final String SUPER_TYPE = "sling:resourceSuperType";

    private static final String REQUIREMENT = "sch/Requirement";

    private static final String FORM_ITEM = "sch/FormItem";

    private static final String VERSION_PATH = "/Schemas/timeOffRequest/v1";

    private static final String SUBMISSION_PATH = "/Submissions/ab/cd/ef/aRequest";

    private static final String REQUESTER = "demo-requester";

    private static final String DETAILS = "details";

    private static final String APPROVAL = "approval";

    private static final String APPROVERS = "time-off-approvers";

    private static final String APPROVER = "priya";

    private static final String DECISION = "decision";

    private static final String START_DATE = "details/startDate";

    private static final String END_DATE = "details/endDate";

    private static final String EXTRACTION = "extraction";

    private static final String EXTRACTED = "extracted";

    private static final String CONFIRMS_CATEGORY = "confirmsCategory";

    private static final String DOCTORS_NOTE = "doctorsNote";

    private static final String REQUIREMENTS = "requirements";

    private static final String PROVENANCE = "provenance";

    /** The schema parts these tests hide, by name; everything else applies. */
    private final Set<String> hidden = new HashSet<>();

    // JCR-backed: the submission points at its schema version with a real REFERENCE
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private ConditionEvaluator evaluator;

    private SubmissionFormServlet servlet;

    @BeforeEach
    void setUp() throws Exception
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, Schema.class,
            SchemaVersion.class, FormRequirement.class, DocumentRequirement.class, ApprovalRequirement.class,
            Section.class, Question.class, AnswerOption.class, Answer.class, Document.class, DocumentVersion.class,
            File.class, Review.class, Submission.class, ClassificationRequirement.class,
            ValidationRequirement.class, Extraction.class, Context.class);
        // Whether a request may still be answered is read from its lifecycle tag, which needs the view the
        // tags bundle provides
        Tagging.enable(this.context);
        // Everything applies unless a test hides it. What these tests check is that the servlet asks about every
        // part, and follows the answer.
        this.evaluator = Mockito.mock(ConditionEvaluator.class);
        Mockito.when(this.evaluator.applies(Mockito.any(), Mockito.any()))
            .thenAnswer(call -> !this.hidden.contains(((Content) call.getArgument(0)).getName()));
        this.servlet = new SubmissionFormServlet(this.evaluator);
        // The shipped describers, so these tests pin the whole document a reader is served
        this.servlet.setDescribers(builtinDescribers());

        this.context.create().resource("/Schemas/timeOffRequest", Map.of(
            TYPE, Schema.RESOURCE_TYPE, "title", "Time off request"));
        this.context.create().resource(VERSION_PATH, Map.of(
            TYPE, SchemaVersion.RESOURCE_TYPE, "version", "1.0", "tags", new String[] {"active"}));
        this.context.create().resource(VERSION_PATH + "/" + DETAILS, Map.of(
            TYPE, FormRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT,
            "label", "Request details", "description", "When and why."));
        this.context.create().resource(VERSION_PATH + "/" + START_DATE, Map.of(
            TYPE, Question.RESOURCE_TYPE, SUPER_TYPE, FORM_ITEM,
            "text", "Which day does your time off start?", "dataType", "date", "minAnswers", 1L));
        this.context.create().resource(VERSION_PATH + "/" + END_DATE, Map.of(
            TYPE, Question.RESOURCE_TYPE, SUPER_TYPE, FORM_ITEM,
            "text", "Which day are you back?", "dataType", "date"));
        this.context.create().resource(VERSION_PATH + "/doctorsNote", Map.of(
            TYPE, DocumentRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Doctor's note"));
        // The same kind of requirement, but saying what it takes and offering a template
        this.context.create().resource(VERSION_PATH + "/signedForm", Map.of(
            TYPE, DocumentRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Signed form",
            "acceptedFileTypes", new String[] {"application/pdf", "image/png"}));
        this.context.create().resource(VERSION_PATH + "/signedForm/template", Map.of(
            "jcr:primaryType", "nt:file"));
        this.context.create().resource(VERSION_PATH + "/signedForm/template/jcr:content", Map.of(
            "jcr:primaryType", "nt:resource", "jcr:mimeType", "application/pdf"));
        this.context.create().resource(VERSION_PATH + "/" + APPROVAL, Map.of(
            TYPE, ApprovalRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Approval",
            "approverGroup", APPROVERS));

        this.context.create().resource(SUBMISSION_PATH, Map.of(
            TYPE, Submission.RESOURCE_TYPE, "title", "A long weekend", "createdBy", REQUESTER,
            "tags", new String[] {"draft"}));
        reference(SUBMISSION_PATH, VERSION_PATH, "schemaVersion");
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
        // The pair as stored. An absent maximum reads as its default, 1.
        assertEquals(1, startDate.getInt("minAnswers"));
        assertEquals(1, startDate.getInt("maxAnswers"));
        assertEquals("2026-10-06", startDate.getJsonArray("value").getString(0));
        // Unanswered, said as an empty list
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
    void omitsTheConstraintsTheSchemaDoesNotState() throws IOException
    {
        final JsonObject startDate = item(requirement(form(REQUESTER), DETAILS), "startDate");

        assertFalse(startDate.containsKey("minValue"));
        assertFalse(startDate.containsKey("maxValue"));
        assertFalse(startDate.containsKey("pattern"));
        assertFalse(startDate.containsKey("patternMessage"));
    }

    @Test
    void offersNothingForAQuestionAnsweredFreely() throws IOException
    {
        assertTrue(item(requirement(form(REQUESTER), DETAILS), "startDate").getJsonArray("options").isEmpty());
    }

    @Test
    void offersTheAnswersAQuestionDeclares() throws IOException
    {
        final String duration = VERSION_PATH + "/" + DETAILS + "/duration";
        this.context.create().resource(duration, Map.of(
            TYPE, Question.RESOURCE_TYPE, SUPER_TYPE, FORM_ITEM, "text", "How long?", "dataType", "text"));
        this.context.create().resource(duration + "/half", Map.of(
            TYPE, AnswerOption.RESOURCE_TYPE, "value", "half-day", "label", "Half day"));
        // The label falls back to the value, so an option may declare only the one string
        this.context.create().resource(duration + "/several", Map.of(
            TYPE, AnswerOption.RESOURCE_TYPE, "value", "multiple-days"));

        final JsonArray offered = item(requirement(form(REQUESTER), DETAILS), "duration").getJsonArray("options");

        assertEquals(2, offered.size());
        assertEquals("half-day", offered.getJsonObject(0).getString("value"));
        assertEquals("Half day", offered.getJsonObject(0).getString("label"));
        assertEquals("multiple-days", offered.getJsonObject(1).getString("value"));
        assertEquals("multiple-days", offered.getJsonObject(1).getString("label"));
    }

    @Test
    void omitsABoundThatIsNotFinite() throws IOException
    {
        this.context.create().resource(VERSION_PATH + "/" + DETAILS + "/daysAway", Map.of(
            TYPE, Question.RESOURCE_TYPE, SUPER_TYPE, FORM_ITEM, "text", "How many days?", "dataType", "double",
            "minValue", 1.0d, "maxValue", Double.POSITIVE_INFINITY));

        final JsonObject question = item(requirement(form(REQUESTER), DETAILS), "daysAway");

        assertEquals(1.0d, question.getJsonNumber("minValue").doubleValue());
        assertFalse(question.containsKey("maxValue"));
    }

    @Test
    void tellsEachQuestionWhereItsAnswerGoes() throws IOException
    {
        assertEquals(START_DATE, item(requirement(form(REQUESTER), DETAILS), "startDate").getString("path"));
    }

    @Test
    void leavesOutAQuestionThatDoesNotApply() throws IOException
    {
        // The demo's own shape: the return date is asked only when the absence covers several days
        this.hidden.add("endDate");

        final JsonArray items = requirement(form(REQUESTER), DETAILS).getJsonArray("items");

        assertEquals(Set.of("startDate"), names(items));
    }

    @Test
    void leavesOutAWholeRequirementThatDoesNotApply() throws IOException
    {
        this.hidden.add(DOCTORS_NOTE);
        this.hidden.add("signedForm");
        this.hidden.add(APPROVAL);

        assertEquals(Set.of(DETAILS), names(form(REQUESTER).getJsonArray("requirements")));
    }

    @Test
    void namesEveryRequirementEvenWhenNothingDescribesItsKind() throws Exception
    {
        // Before the first describer arrives, the registry leaves the list unset
        this.servlet = new SubmissionFormServlet(this.evaluator);

        final JsonObject form = form(REQUESTER);

        assertEquals(Set.of(DETAILS, DOCTORS_NOTE, "signedForm", APPROVAL),
            names(form.getJsonArray("requirements")));
        final JsonObject note = requirement(form, DOCTORS_NOTE);
        assertEquals(DocumentRequirement.RESOURCE_TYPE, note.getString("type"));
        assertEquals("Doctor's note", note.getString("label"));
        // Nothing of the kind's own, because nothing claimed it
        assertFalse(note.containsKey("acceptedFileTypes"));
        assertFalse(requirement(form, DETAILS).containsKey("items"));
    }

    @Test
    void skipsAndRecordsADescriberThatFails() throws Exception
    {
        final IllegalStateException fault = new IllegalStateException("broken");
        this.servlet.setDescribers(withBuiltins(new FailingDescriber(fault)));
        final ErrorLoggerService recorder = recordInto();
        try {
            final JsonObject note = requirement(form(REQUESTER), DOCTORS_NOTE);

            assertTrue(note.containsKey("acceptedFileTypes"), "the other describers still ran");
            assertFalse(note.containsKey(FailingDescriber.HALF_WRITTEN_KEY), "what it wrote before failing is dropped");
            final ArgumentCaptor<Throwable> recorded = ArgumentCaptor.forClass(Throwable.class);
            Mockito.verify(recorder, Mockito.atLeastOnce())
                .logError(recorded.capture(), Mockito.any(ErrorContext.class));
            assertEquals(fault, recorded.getValue());
        } finally {
            ErrorLogger.unsetService(recorder);
        }
    }

    @Test
    void servesTheFormWhenADescriberCannotSayWhatItHandles() throws Exception
    {
        this.servlet.setDescribers(withBuiltins(new UndecidedDescriber()));
        final ErrorLoggerService recorder = recordInto();
        try {
            final JsonObject form = form(REQUESTER);

            assertEquals(Set.of(DETAILS, DOCTORS_NOTE, "signedForm", APPROVAL),
                names(form.getJsonArray("requirements")));
            assertTrue(requirement(form, DOCTORS_NOTE).containsKey("acceptedFileTypes"));
            Mockito.verify(recorder, Mockito.atLeastOnce()).logError(Mockito.any(IllegalStateException.class),
                Mockito.any(ErrorContext.class));
        } finally {
            ErrorLogger.unsetService(recorder);
        }
    }

    @Test
    void letsADescriberRewordACommonField() throws Exception
    {
        this.servlet.setDescribers(withBuiltins(new KeyWriter("description", "Worded by its kind")));

        assertEquals("Worded by its kind", requirement(form(REQUESTER), DOCTORS_NOTE).getString("description"));
    }

    @Test
    void keepsTheValueOfTheDescriberAppliedLast() throws Exception
    {
        this.servlet.setDescribers(List.of(new KeyWriter("shade", "first"), new KeyWriter("shade", "last")));

        assertEquals("last", requirement(form(REQUESTER), DOCTORS_NOTE).getString("shade"));
    }

    @Test
    void describesRequirementsThatHoldNoQuestions() throws IOException
    {
        final JsonObject note = requirement(form(REQUESTER), DOCTORS_NOTE);

        assertEquals(DocumentRequirement.RESOURCE_TYPE, note.getString("type"));
        assertEquals("Doctor's note", note.getString("label"));
        assertFalse(note.containsKey("items"));
        // Any type, said as an empty list
        assertTrue(note.getJsonArray("acceptedFileTypes").isEmpty());
        assertFalse(note.containsKey("template"));
        assertTrue(note.getJsonArray("attached").isEmpty());
    }

    @Test
    void saysWhetherEachDocumentIsDemandedOrMerelyOffered() throws IOException
    {
        this.context.create().resource(VERSION_PATH + "/sponsorLetter", Map.of(
            TYPE, DocumentRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Sponsor letter",
            "required", false));

        final JsonObject form = form(REQUESTER);

        // A requirement without the flag is required, and the form says so
        assertTrue(requirement(form, DOCTORS_NOTE).getBoolean("required"));
        assertFalse(requirement(form, "sponsorLetter").getBoolean("required"));
        // Only document requirements carry the key. Each question of a form says whether it is required.
        assertFalse(requirement(form, DETAILS).containsKey("required"));
    }

    @Test
    void namesWhatHasAlreadyBeenAttachedForARequirement() throws IOException
    {
        final Resource document = this.context.create().resource(SUBMISSION_PATH + "/d1", Map.of(
            TYPE, Document.RESOURCE_TYPE, "title", "note.pdf"));
        reference(document.getPath(), VERSION_PATH + "/doctorsNote", "fulfills");
        uploaded(document);

        final JsonObject attached = requirement(form(REQUESTER), DOCTORS_NOTE).getJsonArray("attached")
            .getJsonObject(0);
        assertEquals("note.pdf", attached.getString("title"));
    }

    @Test
    void pointsAtTheNewestFileOfAnAttachment() throws IOException
    {
        final Resource document = this.context.create().resource(SUBMISSION_PATH + "/d5", Map.of(
            TYPE, Document.RESOURCE_TYPE, "title", "note.pdf"));
        reference(document.getPath(), VERSION_PATH + "/doctorsNote", "fulfills");
        for (final String version : List.of("old", "new")) {
            this.context.create().resource(document.getPath() + "/" + version,
                Map.of(TYPE, DocumentVersion.RESOURCE_TYPE));
            this.context.create().resource(document.getPath() + "/" + version + "/file",
                Map.of(TYPE, File.RESOURCE_TYPE));
            this.context.create().resource(document.getPath() + "/" + version + "/file/uploadedFile",
                Map.of("jcr:primaryType", "nt:file"));
        }

        assertEquals(document.getPath() + "/new/file/uploadedFile", requirement(form(REQUESTER), DOCTORS_NOTE)
            .getJsonArray("attached").getJsonObject(0).getString("path"));
    }

    @Test
    void doesNotListADocumentNothingWasUploadedInto() throws IOException
    {
        final Resource document = this.context.create().resource(SUBMISSION_PATH + "/d0", Map.of(
            TYPE, Document.RESOURCE_TYPE, "title", "empty"));
        reference(document.getPath(), VERSION_PATH + "/doctorsNote", "fulfills");

        assertEquals(0, requirement(form(REQUESTER), DOCTORS_NOTE).getJsonArray("attached").size());
    }

    @Test
    void fallsBackOnANameForAnUntitledAttachment() throws IOException
    {
        // A document not created by the attach workflow may have no title
        final Resource document = this.context.create().resource(SUBMISSION_PATH + "/d2", Map.of(
            TYPE, Document.RESOURCE_TYPE));
        reference(document.getPath(), VERSION_PATH + "/doctorsNote", "fulfills");
        uploaded(document);

        assertEquals("d2", requirement(form(REQUESTER), DOCTORS_NOTE).getJsonArray("attached").getJsonObject(0)
            .getString("title"));
    }

    @Test
    void leavesOutADocumentAttachedForSomeOtherRequirement() throws IOException
    {
        final Resource document = this.context.create().resource(SUBMISSION_PATH + "/d3", Map.of(
            TYPE, Document.RESOURCE_TYPE, "title", "form.pdf"));
        reference(document.getPath(), VERSION_PATH + "/signedForm", "fulfills");
        uploaded(document);

        assertTrue(requirement(form(REQUESTER), DOCTORS_NOTE).getJsonArray("attached").isEmpty());
        assertFalse(requirement(form(REQUESTER), "signedForm").getJsonArray("attached").isEmpty());
    }

    @Test
    void leavesOutADocumentThatFulfillsNothing() throws IOException
    {
        this.context.create().resource(SUBMISSION_PATH + "/d4", Map.of(
            TYPE, Document.RESOURCE_TYPE, "title", "stray.pdf"));

        assertTrue(requirement(form(REQUESTER), DOCTORS_NOTE).getJsonArray("attached").isEmpty());
    }

    @Test
    void saysWhatADocumentRequirementTakesAndWhatItOffersToStartFrom() throws IOException
    {
        final JsonObject signed = requirement(form(REQUESTER), "signedForm");

        assertEquals(List.of("application/pdf", "image/png"), signed.getJsonArray("acceptedFileTypes").stream()
            .map(value -> ((JsonString) value).getString())
            .collect(Collectors.toList()));
        assertEquals(VERSION_PATH + "/signedForm/template", signed.getString("template"));
        assertEquals(VERSION_PATH + "/signedForm", signed.getString("path"));
        // The node is called "template", so the download is named after the requirement, with its type's extension
        assertEquals("Signed form.pdf", signed.getString("templateName"));
    }

    @Test
    void namesATemplateOfNoKnownTypeAfterTheRequirementAlone() throws IOException
    {
        // No label and no declared type: the requirement's name, with nothing guessed after it
        this.context.create().resource(VERSION_PATH + "/waiver", Map.of(
            TYPE, DocumentRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT));
        this.context.create().resource(VERSION_PATH + "/waiver/template", Map.of("jcr:primaryType", "nt:file"));
        // A declared type with no known extension
        this.context.create().resource(VERSION_PATH + "/scan", Map.of(
            TYPE, DocumentRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Scan"));
        this.context.create().resource(VERSION_PATH + "/scan/template", Map.of("jcr:primaryType", "nt:file"));
        this.context.create().resource(VERSION_PATH + "/scan/template/jcr:content", Map.of(
            "jcr:primaryType", "nt:resource", "jcr:mimeType", "application/x-iap-unknown"));

        final JsonObject form = form(REQUESTER);

        assertEquals("waiver", requirement(form, "waiver").getString("templateName"));
        assertEquals("Scan", requirement(form, "scan").getString("templateName"));
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
        assertFalse(form("demo-approver").getBoolean("editable"));
    }

    @Test
    void reportsNotEditableOnceItIsNoLongerADraft() throws IOException
    {
        modify(SUBMISSION_PATH, "tags", new String[] {"submitted"});

        assertFalse(form(REQUESTER).getBoolean("editable"));
    }

    @Test
    void passesOverAnAnswerWhoseQuestionIsGone() throws IOException
    {
        // A question removed from the schema leaves its answer behind
        this.context.create().resource(SUBMISSION_PATH + "/orphan", Map.of(
            TYPE, Answer.RESOURCE_TYPE, "value", new String[] {"stale"}));

        assertTrue(item(requirement(form(REQUESTER), DETAILS), "startDate").getJsonArray("value").isEmpty());
    }

    @Test
    void refusesWhenTheSchemaVersionCannotBeRead() throws IOException, PersistenceException
    {
        // A reference that resolves to nothing, as one to a version this session may not read does
        Objects.requireNonNull(this.context.resourceResolver().getResource(SUBMISSION_PATH))
            .adaptTo(ModifiableValueMap.class)
            .put("schemaVersion", "00000000-dead-0000-0000-000000000000");
        this.context.resourceResolver().commit();
        this.context.resourceResolver().refresh();
        final MockSlingJakartaHttpServletResponse response = new MockSlingJakartaHttpServletResponse();

        this.servlet.doGet(request(Objects.requireNonNull(
            this.context.resourceResolver().getResource(SUBMISSION_PATH)), REQUESTER), response);

        assertEquals(MockSlingJakartaHttpServletResponse.SC_FORBIDDEN, response.getStatus());
    }

    @Test
    void saysWhoAnApprovalWaitsOnWhileNobodyHasDecided() throws IOException
    {
        final JsonObject approval = requirement(form(REQUESTER), APPROVAL);

        assertEquals(ApprovalRequirement.RESOURCE_TYPE, approval.getString("type"));
        assertEquals(APPROVERS, approval.getString("approverGroup"));
        assertFalse(approval.containsKey(DECISION));
    }

    @Test
    void reportsTheDecisionOnceSomebodyHasMadeIt() throws IOException
    {
        approvedBy("r1", APPROVER, 27);

        final JsonObject decision = decisionOn(APPROVAL);

        assertTrue(decision.getBoolean("approved"));
        assertEquals(APPROVER, decision.getString("by"));
        // In the format the resource JSON gives every date
        assertEquals("2026-08-27T09:15:30.500-05:00", decision.getString("at"));
    }

    @Test
    void reportsARejectionAsADecisionThatDidNotApprove() throws IOException
    {
        rejectedBy("r1", APPROVER, 27);

        final JsonObject decision = decisionOn(APPROVAL);

        assertFalse(decision.getBoolean("approved"));
        assertEquals(APPROVER, decision.getString("by"));
    }

    @Test
    void reportsADecisionWithNoRecordedDateWithoutOne() throws IOException
    {
        final Resource undated = this.context.create().resource(SUBMISSION_PATH + "/r1", Map.of(
            TYPE, Review.RESOURCE_TYPE, "reviewer", APPROVER, "tags", new String[] {Submission.APPROVED_TAG}));
        about(undated, APPROVAL);

        final JsonObject decision = decisionOn(APPROVAL);

        assertEquals(APPROVER, decision.getString("by"));
        assertFalse(decision.containsKey("at"));
    }

    @Test
    void grantsAnApprovalALaterDecisionGranted() throws IOException
    {
        rejectedBy("r1", APPROVER, 27);
        approvedBy("r2", "sam", 28);

        final JsonObject decision = decisionOn(APPROVAL);

        assertTrue(decision.getBoolean("approved"));
        assertEquals("sam", decision.getString("by"));
    }

    @Test
    void withdrawsAnApprovalALaterDecisionRejected() throws IOException
    {
        approvedBy("r1", APPROVER, 27);
        rejectedBy("r2", "sam", 28);

        final JsonObject decision = decisionOn(APPROVAL);

        assertFalse(decision.getBoolean("approved"));
        assertEquals("sam", decision.getString("by"));
    }

    @Test
    void keepsTheDecisionAReviewStillOpenHasNotReplaced() throws IOException
    {
        approvedBy("r1", APPROVER, 27);
        about(review("r2", "sam", 28, "in-progress"), APPROVAL);

        final JsonObject decision = decisionOn(APPROVAL);

        assertTrue(decision.getBoolean("approved"));
        assertEquals(APPROVER, decision.getString("by"));
    }

    @Test
    void reportsNoDecisionWhileTheOnlyReviewIsOpen() throws IOException
    {
        about(review("r1", APPROVER, 27, "in-progress"), APPROVAL);

        assertFalse(requirement(form(REQUESTER), APPROVAL).containsKey(DECISION));
    }

    @Test
    void leavesOutADecisionOnAnotherRequirement() throws IOException
    {
        about(review("r1", APPROVER, 27, Submission.APPROVED_TAG), DOCTORS_NOTE);

        assertFalse(requirement(form(REQUESTER), APPROVAL).containsKey(DECISION));
    }

    @Test
    void leavesOutADecisionOnNoRequirement() throws IOException
    {
        review("r1", APPROVER, 27, Submission.APPROVED_TAG);

        assertFalse(requirement(form(REQUESTER), APPROVAL).containsKey(DECISION));
    }

    @Test
    void saysAnApprovalIsNotNarrowedToAGroupRatherThanOmittingIt() throws IOException
    {
        this.context.create().resource(VERSION_PATH + "/rebApproval", Map.of(
            TYPE, ApprovalRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "REB approval"));

        assertEquals("", requirement(form(REQUESTER), "rebApproval").getString("approverGroup"));
    }

    @Test
    void saysAFormNamingItsDocumentsReadsThem() throws IOException
    {
        assertFalse(form(REQUESTER).getBoolean("readsDocuments"));

        modify(VERSION_PATH + "/" + DETAILS, "documents", new String[] {DOCTORS_NOTE});

        assertTrue(form(REQUESTER).getBoolean("readsDocuments"));
    }

    // A model decides it and nobody fills it in, so it reads the documents but is not shown
    @Test
    void saysAValidationReadsTheDocumentsWithoutShowingIt() throws IOException
    {
        this.context.create().resource(VERSION_PATH + "/isNote", Map.of(
            TYPE, ValidationRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Is it a note?",
            "prompt", "Is this a doctor's note?", "document", DOCTORS_NOTE));

        final JsonObject form = form(REQUESTER);

        assertTrue(form.getBoolean("readsDocuments"));
        assertFalse(names(form.getJsonArray(REQUIREMENTS)).contains("isNote"));
    }

    @Test
    void saysNothingAboutAReadingNobodyAskedFor() throws IOException
    {
        assertFalse(form(REQUESTER).containsKey(EXTRACTION));
    }

    @Test
    void saysWhereAReadingThatFailedStands() throws IOException
    {
        modify(SUBMISSION_PATH, "extractionStatus", "failed");
        modify(SUBMISSION_PATH, "extractionMessage", "The document could not be read. Please try again");
        modify(SUBMISSION_PATH, "extractionBlocked", true);
        modify(SUBMISSION_PATH, "extractionReadingClaimed", true);
        parsed("d1", "failed", "The daemon is down");

        final JsonObject reading = form(REQUESTER).getJsonObject(EXTRACTION);

        assertEquals("failed", reading.getString("status"));
        assertEquals("The document could not be read. Please try again", reading.getString("message"));
        assertTrue(reading.getBoolean("retryable"));
        assertEquals("The daemon is down", reading.getString("detail"));
        assertTrue(reading.getBoolean("parsed"));
        assertTrue(reading.getBoolean("reading"));
        assertTrue(reading.getBoolean("blocked"));
    }

    @Test
    void saysAParseIsStillGoing() throws IOException
    {
        modify(SUBMISSION_PATH, "extractionStatus", "running");
        parsed("d1", "queued", null);
        // A failure with no reason of its own, and documents with no file to read yet
        parsed("d2", "failed", " ");
        this.context.create().resource(SUBMISSION_PATH + "/d3", Map.of(TYPE, Document.RESOURCE_TYPE));
        this.context.create().resource(SUBMISSION_PATH + "/d4", Map.of(TYPE, Document.RESOURCE_TYPE));
        this.context.create().resource(SUBMISSION_PATH + "/d4/v1", Map.of(TYPE, DocumentVersion.RESOURCE_TYPE));

        final JsonObject reading = form(REQUESTER).getJsonObject(EXTRACTION);

        assertEquals("running", reading.getString("status"));
        assertFalse(reading.getBoolean("parsed"));
        assertTrue(reading.getBoolean("retryable"));
        assertFalse(reading.containsKey("detail"));
        assertFalse(reading.containsKey("message"));
        assertFalse(reading.getBoolean("reading"));
        assertFalse(reading.getBoolean("blocked"));
    }

    @Test
    void saysWhichFormsAModelFillsIn() throws IOException
    {
        assertFalse(requirement(form(REQUESTER), DETAILS).getBoolean(EXTRACTED));

        this.context.create().resource(VERSION_PATH + "/" + DETAILS + "/why", Map.of(
            TYPE, Question.RESOURCE_TYPE, SUPER_TYPE, FORM_ITEM, "text", "Why?", "extractionPrompt", " "));
        assertFalse(requirement(form(REQUESTER), DETAILS).getBoolean(EXTRACTED), "a blank prompt asks nothing");

        this.context.create().resource(VERSION_PATH + "/" + DETAILS + "/when", Map.of(
            TYPE, Section.RESOURCE_TYPE, SUPER_TYPE, FORM_ITEM, "title", "Dates"));
        this.context.create().resource(VERSION_PATH + "/" + DETAILS + "/when/returning", Map.of(
            TYPE, Question.RESOURCE_TYPE, SUPER_TYPE, FORM_ITEM, "text", "Returning?",
            "extractionPrompt", "When does the leave end?"));
        assertFalse(requirement(form(REQUESTER), DETAILS).getBoolean(EXTRACTED), "no document to read it from");

        modify(VERSION_PATH + "/" + DETAILS, "documents", new String[] {DOCTORS_NOTE});
        assertTrue(requirement(form(REQUESTER), DETAILS).getBoolean(EXTRACTED));
    }

    @Test
    void saysWhichFormHoldsTheCategoryToConfirm() throws IOException
    {
        this.context.create().resource(VERSION_PATH + "/notes", Map.of(
            TYPE, FormRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Notes"));
        this.context.create().resource(VERSION_PATH + "/kind", Map.of(
            TYPE, ClassificationRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Kind of leave",
            "prompt", "What kind of leave is this?", "document", DOCTORS_NOTE));
        assertFalse(requirement(form(REQUESTER), DETAILS).getBoolean(CONFIRMS_CATEGORY), "it names no question");

        reference(VERSION_PATH + "/kind", VERSION_PATH + "/" + START_DATE, "question");
        final JsonObject form = form(REQUESTER);

        assertTrue(requirement(form, DETAILS).getBoolean(CONFIRMS_CATEGORY));
        assertFalse(requirement(form, "notes").getBoolean(CONFIRMS_CATEGORY));
        assertFalse(names(form.getJsonArray(REQUIREMENTS)).contains("kind"), "the submitter confirms it in its form");
    }

    @Test
    void showsNoProvenanceForAnAnswerNothingWasReadFor() throws IOException
    {
        answer(START_DATE, "2026-10-06");

        assertFalse(item(requirement(form(REQUESTER), DETAILS), "startDate").containsKey(PROVENANCE));
    }

    @Test
    void showsNoProvenanceWhenTheNewestRunFoundNothing() throws IOException
    {
        final Resource answer = emptyAnswer(START_DATE);
        run(answer, "r1", Map.of("extractedValues", new String[] {"2026-10-06"}));
        run(answer, "r2", Map.of());

        assertFalse(item(requirement(form(REQUESTER), DETAILS), "startDate").containsKey(PROVENANCE));
    }

    @Test
    void showsWhatTheNewestRunSuggestedAndTheQuotesBehindIt() throws IOException
    {
        final Resource document = parsed("d1", "completed", null);
        this.context.create().resource(document.getPath() + "/v1/file/pdfFile", Map.of("jcr:primaryType", "nt:file"));
        final Resource answer = emptyAnswer(START_DATE);
        final Resource run = run(answer, "r1", Map.of("extractedValues", new String[] {"2026-10-06"},
            "confidence", 0.8, "reasoning", "The note says so"));
        sources(run, document.getPath() + "/v1");
        this.context.create().resource(run.getPath() + "/q1", Map.of(TYPE, Context.RESOURCE_TYPE,
            "quote", "off from October 6", "page", 3L, "header", " Leave "));
        this.context.create().resource(run.getPath() + "/q2", Map.of(TYPE, Context.RESOURCE_TYPE));

        final JsonObject provenance =
            item(requirement(form(REQUESTER), DETAILS), "startDate").getJsonObject(PROVENANCE);

        assertEquals("2026-10-06", provenance.getJsonArray("suggested").getString(0));
        // Nothing answered yet, so the suggestion is what is offered
        assertEquals("2026-10-06", provenance.getJsonArray("offered").getString(0));
        assertEquals(0.8, provenance.getJsonNumber("confidence").doubleValue());
        assertEquals("The note says so", provenance.getString("reasoning"));
        assertFalse(provenance.getBoolean("evidenceRejected"));
        assertFalse(provenance.getBoolean("actedOn"));
        final JsonObject quoted = provenance.getJsonArray("passages").getJsonObject(0);
        assertEquals("off from October 6", quoted.getString("quote"));
        assertEquals("p. 3 · Leave", quoted.getString("cite"));
        assertEquals(document.getPath() + "/v1/file/pdfFile#page=3", quoted.getString("source"));
        final JsonObject bare = provenance.getJsonArray("passages").getJsonObject(1);
        assertEquals("", bare.getString("quote"));
        assertFalse(bare.containsKey("cite"));
        assertEquals(document.getPath() + "/v1/file/pdfFile", bare.getString("source"));
    }

    @Test
    void linksAQuoteToTheRevisionItCameFrom() throws IOException
    {
        final Resource first = parsed("d1", "completed", null);
        final Resource second = parsed("d2", "completed", null);
        for (final Resource document : List.of(first, second)) {
            this.context.create().resource(document.getPath() + "/v1/file/pdfFile",
                Map.of("jcr:primaryType", "nt:file"));
        }
        final Resource run = run(emptyAnswer(START_DATE), "r1", Map.of("extractedValues", new String[] {"x"}));
        sources(run, first.getPath() + "/v1", second.getPath() + "/v1");
        final Resource quote = this.context.create().resource(run.getPath() + "/q1",
            Map.of(TYPE, Context.RESOURCE_TYPE, "quote", "from the second", "header", " "));
        reference(quote.getPath(), second.getPath() + "/v1", "source");

        final JsonObject quoted = item(requirement(form(REQUESTER), DETAILS), "startDate")
            .getJsonObject(PROVENANCE).getJsonArray("passages").getJsonObject(0);

        assertEquals(second.getPath() + "/v1/file/pdfFile", quoted.getString("source"));
        assertFalse(quoted.containsKey("cite"));
    }

    @Test
    void offersNothingTheSubmitterAlreadyTook() throws IOException
    {
        final Resource answer = this.context.create().resource(SUBMISSION_PATH + "/taken", Map.of(
            TYPE, Answer.RESOURCE_TYPE, "value", new String[] {"2026-10-06"}));
        reference(answer.getPath(), VERSION_PATH + "/" + START_DATE, "question");
        // Read from a revision with no PDF, by a run that kept no confidence and gave no reasoning
        final Resource document = parsed("d1", "completed", null);
        final Resource run = run(answer, "r1", Map.of("extractedValues", new String[] {"2026-10-06"},
            "editDistance", 0L, "evidenceRejected", true));
        sources(run, document.getPath() + "/v1");
        this.context.create().resource(run.getPath() + "/q1", Map.of(TYPE, Context.RESOURCE_TYPE, "quote", "x"));

        final JsonObject provenance =
            item(requirement(form(REQUESTER), DETAILS), "startDate").getJsonObject(PROVENANCE);

        assertTrue(provenance.getJsonArray("offered").isEmpty());
        assertTrue(provenance.getBoolean("actedOn"));
        assertTrue(provenance.getBoolean("evidenceRejected"));
        assertEquals(0.0, provenance.getJsonNumber("confidence").doubleValue());
        assertFalse(provenance.containsKey("reasoning"));
        assertFalse(provenance.getJsonArray("passages").getJsonObject(0).containsKey("source"));
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
     * A request from a named person. The session holds the canonical user id, which a creator is compared against.
     * The resolver reports the name as it was typed at login, here in upper case, so a servlet reading the wrong one
     * fails.
     */
    private MockSlingJakartaHttpServletRequest request(final Resource resource, final String reader)
    {
        final Session session = Mockito.mock(Session.class);
        Mockito.when(session.getUserID()).thenReturn(reader);
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
                return Session.class.equals(type) ? type.cast(session) : super.adaptTo(type);
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

    private void answer(final String questionPath, final String value)
    {
        final Resource answer = this.context.create().resource(SUBMISSION_PATH + "/" + value.hashCode(), Map.of(
            TYPE, Answer.RESOURCE_TYPE, "value", new String[] {value}));
        reference(answer.getPath(), VERSION_PATH + "/" + questionPath, "question");
    }

    private JsonObject decisionOn(final String requirement) throws IOException
    {
        return requirement(form(REQUESTER), requirement).getJsonObject(DECISION);
    }

    private void approvedBy(final String name, final String reviewer, final int day)
    {
        about(review(name, reviewer, day, Submission.APPROVED_TAG), APPROVAL);
    }

    private void rejectedBy(final String name, final String reviewer, final int day)
    {
        about(review(name, reviewer, day, Review.REJECTED_TAG), APPROVAL);
    }

    /**
     * A review naming no requirement yet, created on the given day of August 2026.
     *
     * @param name its node name
     * @param reviewer who wrote it
     * @param day the day of the month it was created
     * @param tags its state tags
     * @return the review
     */
    private Resource review(final String name, final String reviewer, final int day, final String... tags)
    {
        final Calendar created = new GregorianCalendar(TimeZone.getTimeZone("GMT-05:00"));
        created.clear();
        created.set(2026, Calendar.AUGUST, day, 9, 15, 30);
        created.set(Calendar.MILLISECOND, 500);
        final Resource review = this.context.create().resource(SUBMISSION_PATH + "/" + name, Map.of(
            TYPE, Review.RESOURCE_TYPE, "reviewer", reviewer, "tags", tags));
        // Set afterwards: creating a JCR resource ignores a jcr:created given with it
        modify(review.getPath(), "jcr:created", created);
        return review;
    }

    private void about(final Resource review, final String requirement)
    {
        reference(review.getPath(), VERSION_PATH + "/" + requirement, "requirement");
    }

    /** A document with one revision whose file has the given parse status and error. */
    private Resource parsed(final String name, final String status, final String error)
    {
        final Resource document = this.context.create().resource(SUBMISSION_PATH + "/" + name,
            Map.of(TYPE, Document.RESOURCE_TYPE));
        this.context.create().resource(document.getPath() + "/v1", Map.of(TYPE, DocumentVersion.RESOURCE_TYPE));
        final Map<String, Object> file = new HashMap<>(Map.of(TYPE, File.RESOURCE_TYPE, "parseStatus", status));
        if (error != null) {
            file.put("parseError", error);
        }
        this.context.create().resource(document.getPath() + "/v1/file", file);
        return document;
    }

    /** An answer with no value of its own, as the form or createParts leaves it. */
    private Resource emptyAnswer(final String questionPath)
    {
        final Resource answer = this.context.create().resource(SUBMISSION_PATH + "/empty",
            Map.of(TYPE, Answer.RESOURCE_TYPE));
        reference(answer.getPath(), VERSION_PATH + "/" + questionPath, "question");
        return answer;
    }

    private Resource run(final Resource answer, final String name, final Map<String, Object> properties)
    {
        final Map<String, Object> all = new HashMap<>(properties);
        all.put(TYPE, Extraction.RESOURCE_TYPE);
        return this.context.create().resource(answer.getPath() + "/" + name, all);
    }

    /** Says which revisions a run read, as the references a reading stores. */
    private void sources(final Resource run, final String... versionPaths)
    {
        try {
            final String[] identifiers = new String[versionPaths.length];
            for (int i = 0; i < versionPaths.length; ++i) {
                identifiers[i] = Objects.requireNonNull(this.context.resourceResolver().getResource(versionPaths[i])
                    .adaptTo(Node.class)).getIdentifier();
            }
            Objects.requireNonNull(run.adaptTo(Node.class)).setProperty("sources", identifiers);
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

    /**
     * The describers this module ships, built the way the service registry builds them.
     *
     * @return the three built-in describers
     */
    private List<RequirementDescriber> builtinDescribers()
    {
        return List.of(new FormRequirementDescriber(this.evaluator),
            new DocumentRequirementDescriber(this.context.getService(MimeTypeService.class)),
            new ApprovalRequirementDescriber());
    }

    /**
     * The built-in describers, followed by one more.
     *
     * @param last the describer applied after the built-ins
     * @return the describers, in the order they are applied
     */
    private List<RequirementDescriber> withBuiltins(final RequirementDescriber last)
    {
        return Stream.concat(builtinDescribers().stream(), Stream.of(last)).collect(Collectors.toList());
    }

    private static ErrorLoggerService recordInto()
    {
        final ErrorLoggerService recorder = Mockito.mock(ErrorLoggerService.class);
        ErrorLogger.setService(recorder);
        return recorder;
    }

    /** A describer that claims every requirement and adds one key to it. */
    private record KeyWriter(String key, String value) implements RequirementDescriber
    {
        @Override
        public boolean handles(final Requirement requirement)
        {
            return true;
        }

        @Override
        public void describe(final Requirement requirement,
            final Submission submission, final JsonObjectBuilder json)
        {
            json.add(this.key, this.value);
        }
    }

    /** A describer that claims every requirement and fails halfway through describing it. */
    private record FailingDescriber(RuntimeException fault) implements RequirementDescriber
    {
        static final String HALF_WRITTEN_KEY = "halfWritten";

        @Override
        public boolean handles(final Requirement requirement)
        {
            return true;
        }

        @Override
        public void describe(final Requirement requirement,
            final Submission submission, final JsonObjectBuilder json)
        {
            json.add(HALF_WRITTEN_KEY, true);
            throw this.fault;
        }
    }

    /** A describer that fails when asked whether a requirement is its own. */
    private static final class UndecidedDescriber implements RequirementDescriber
    {
        @Override
        public boolean handles(final Requirement requirement)
        {
            throw new IllegalStateException("undecided");
        }

        @Override
        public void describe(final Requirement requirement,
            final Submission submission, final JsonObjectBuilder json)
        {
            json.add("never", true);
        }
    }

    /**
     * Puts a file in a document, as an upload does: in a version of its own.
     *
     * @param document the document
     */
    private void uploaded(final Resource document)
    {
        this.context.create().resource(document.getPath() + "/v1", Map.of(TYPE, DocumentVersion.RESOURCE_TYPE));
        this.context.create().resource(document.getPath() + "/v1/file", Map.of(TYPE, File.RESOURCE_TYPE));
        this.context.create().resource(document.getPath() + "/v1/file/uploadedFile",
            Map.of("jcr:primaryType", "nt:file"));
    }
}
