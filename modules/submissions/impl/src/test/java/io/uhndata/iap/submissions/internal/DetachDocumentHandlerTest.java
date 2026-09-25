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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Value;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.api.wrappers.ResourceResolverWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Document;
import io.uhndata.iap.submissions.models.DocumentVersion;
import io.uhndata.iap.submissions.models.Evidence;
import io.uhndata.iap.submissions.models.Extraction;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.EventAttachment;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DetachDocumentHandler}: who may remove a document, which requirements they may name, and
 * what is left behind.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class DetachDocumentHandlerTest
{
    private static final String TYPE = "sling:resourceType";

    // A mock repository has no /libs/sch hierarchy to inherit from, so each requirement carries the super type
    private static final String SUPER_TYPE = "sling:resourceSuperType";

    private static final String REQUIREMENT = "sch/Requirement";

    private static final String VERSION_PATH = "/Schemas/timeOffRequest/v1";

    private static final String NOTE = "doctorsNote";

    private static final String NOTE_PATH = VERSION_PATH + "/" + NOTE;

    private static final String SUBMISSION_PATH = "/Submissions/ab/cd/ef/aRequest";

    private static final String REQUESTER = "demo-requester";

    private static final String PDF = "application/pdf";

    private static final byte[] CONTENT = new byte[] {0x25, 0x50, 0x44, 0x46};

    // JCR-backed: what is removed was attached by the real handler, which writes a real REFERENCE and binary
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final AttachDocumentHandler attacher = new AttachDocumentHandler();

    private final DetachDocumentHandler handler = new DetachDocumentHandler();

    private Resource target;

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, Schema.class,
            SchemaVersion.class, FormRequirement.class, DocumentRequirement.class, Document.class, Submission.class,
            Activity.class, DocumentVersion.class, File.class, Answer.class, Extraction.class, Evidence.class);
        Tagging.enable(this.context);
        this.context.create().resource("/Schemas/timeOffRequest", Map.of(
            TYPE, Schema.RESOURCE_TYPE, "title", "Time off request"));
        this.context.create().resource(VERSION_PATH, Map.of(
            TYPE, SchemaVersion.RESOURCE_TYPE, "version", "1.0", "tags", new String[] {"active"}));
        this.context.create().resource(NOTE_PATH, Map.of(
            TYPE, DocumentRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Doctor's note"));
        this.context.create().resource(VERSION_PATH + "/anything", Map.of(
            TYPE, DocumentRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Anything at all"));
        this.context.create().resource(VERSION_PATH + "/details", Map.of(
            TYPE, FormRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Request details"));
        this.target = this.context.create().resource(SUBMISSION_PATH, Map.of(
            TYPE, Submission.RESOURCE_TYPE, "title", "A long weekend", "createdBy", REQUESTER,
            "tags", new String[] {"draft"}));
        reference(this.target, VERSION_PATH, "schemaVersion");
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(DetachDocumentHandler.HANDLER_NAME, this.handler.getName());
    }

    @Test
    void removesTheDocumentWithAllItsVersions() throws Exception
    {
        attach(NOTE, "wrong.pdf");
        attach(NOTE, "also-wrong.pdf");
        final String documentPath = documents().get(0).getPath();

        this.handler.execute(context(payload(NOTE), REQUESTER));

        assertTrue(documents().isEmpty());
        assertNull(this.context.resourceResolver().getResource(documentPath));
    }

    @Test
    void leavesWhatAnswersSomeOtherRequirementAlone() throws Exception
    {
        attach(NOTE, "note.pdf");
        attach("anything", "other.pdf");

        this.handler.execute(context(payload(NOTE), REQUESTER));

        final List<Document> left = documents();
        assertEquals(1, left.size());
        assertEquals("other.pdf", left.get(0).getTitle());
    }

    @Test
    void removesADocumentWhoseRequirementNoLongerApplies() throws Exception
    {
        // Registered before anything adapts the submission, since the adapted model keeps the evaluator it got
        final AtomicBoolean applies = new AtomicBoolean(true);
        final ConditionEvaluator conditions = Mockito.mock(ConditionEvaluator.class);
        Mockito.when(conditions.applies(Mockito.any(), Mockito.any())).thenAnswer(invocation -> applies.get());
        this.context.registerService(ConditionEvaluator.class, conditions);
        attach(NOTE, "note.pdf");
        applies.set(false);

        this.handler.execute(context(payload(NOTE), REQUESTER));

        assertTrue(documents().isEmpty());
    }

    @Test
    void refusesSomebodyElsesRequest()
    {
        assertThrows(NotAuthorizedException.class,
            () -> this.handler.execute(context(payload(NOTE), "somebody-else")));
    }

    @Test
    void refusesARequestThatHasAlreadyBeenSent()
    {
        modify(this.target, "tags", new String[] {"submitted"});

        assertThrows(InvalidStateException.class, () -> this.handler.execute(context(payload(NOTE), REQUESTER)));
    }

    @Test
    void refusesAnEventThatNamesNoRequirement()
    {
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(new HashMap<>(), REQUESTER)));
    }

    @Test
    void refusesARequirementOfAnotherSchema()
    {
        final InvalidPayloadException failure = assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(payload("/Schemas/somethingElse/v1/aNote"), REQUESTER)));
        assertTrue(failure.getMessage().contains("no document requirement"));
    }

    @Test
    void refusesARequirementThatIsNotAskingForAFile()
    {
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(payload("details"), REQUESTER)));
    }

    @Test
    void saysSoWhenNothingIsAttached()
    {
        final InvalidPayloadException failure = assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(payload(NOTE), REQUESTER)));
        assertTrue(failure.getMessage().contains("nothing to remove"));
    }

    @Test
    void passesOverADocumentThatFulfillsNothing()
    {
        this.context.create().resource(SUBMISSION_PATH + "/stray",
            Map.of(TYPE, Document.RESOURCE_TYPE, "jcr:primaryType", "sub:Document", "title", "stray.pdf"));

        assertThrows(InvalidPayloadException.class, () -> this.handler.execute(context(payload(NOTE), REQUESTER)));
        assertNotNull(this.context.resourceResolver().getResource(SUBMISSION_PATH + "/stray"));
    }

    // A reading holds strong references to the revisions it read, and Oak refuses to delete one still referenced.
    // A reading of some other file stays, and so does the answer it filled in.
    @Test
    void dropsTheReadingOfTheFileBeingRemoved() throws Exception
    {
        attach(NOTE, "note.pdf");
        attach("anything", "other.pdf");
        final Resource removed = versionOf("note.pdf");
        final Resource kept = versionOf("other.pdf");
        final Resource fromRemoved = reading("from-the-note", removed);
        final String answerPath = fromRemoved.getParent().getPath();
        // The submitter changed what the model suggested, so the answer is theirs
        modify(fromRemoved.getParent(), "value", new String[] {"two weeks off"});
        modify(fromRemoved, "extractedAnswer", "one week off");
        final Resource quoted = reading("quoted-from-the-note", kept);
        quote(quoted, removed);
        final Resource fromKept = reading("from-the-other", kept);
        quote(fromKept, kept);
        // A child that names no revision, so a missing reference is not treated as a hit
        this.context.create().resource(fromKept.getPath() + "/note", Map.of("jcr:primaryType", "nt:unstructured"));

        this.handler.execute(context(payload(NOTE), REQUESTER));

        final ResourceResolver resolver = this.context.resourceResolver();
        assertNull(resolver.getResource(fromRemoved.getPath()));
        assertNull(resolver.getResource(quoted.getPath()));
        assertNotNull(resolver.getResource(fromKept.getPath()));
        assertNotNull(resolver.getResource(answerPath), "the answer stays; only the reading of this file goes");
        assertTrue(documents().stream().noneMatch(document -> "note.pdf".equals(document.getTitle())));
    }

    // A value the model read out of the file, that nobody confirmed or changed, would otherwise stay behind
    // with no reading and look like something the submitter typed. It goes with the file.
    @Test
    void dropsASuggestionNobodyTouchedWithTheFile() throws Exception
    {
        attach(NOTE);
        final Resource untouched = reading("untouched", versionOf(NOTE));
        modify(untouched.getParent(), "value", new String[] {"one week off"});
        modify(untouched, "extractedAnswer", "one week off");
        final String answer = untouched.getParent().getPath();

        this.handler.execute(context(payload(DOCTORS_NOTE), REQUESTER));

        assertNull(resolver().getResource(answer));
    }

    // Confirming a suggestion makes it the submitter's answer, so only its reading goes
    @Test
    void keepsAConfirmedSuggestion() throws Exception
    {
        attach(NOTE);
        final Resource confirmed = reading("confirmed", versionOf(NOTE));
        modify(confirmed.getParent(), "value", new String[] {"one week off"});
        modify(confirmed, "extractedAnswer", "one week off");
        modify(confirmed, "reviewed", Boolean.TRUE);

        this.handler.execute(context(payload(DOCTORS_NOTE), REQUESTER));

        assertNull(resolver().getResource(confirmed.getPath()));
        assertNotNull(resolver().getResource(confirmed.getParent().getPath()));
    }

    // Another reading still backs the answer, so it is not only a suggestion from the file being removed
    @Test
    void keepsAnAnswerAnotherReadingStillBacks() throws Exception
    {
        attach(NOTE);
        this.attacher.execute(context(attachment("anything", upload("other.pdf")), REQUESTER));
        patchDocumentTypes();
        final Resource fromNote = reading("backed", versionOf(NOTE));
        modify(fromNote.getParent(), "value", new String[] {"one week off"});
        modify(fromNote, "extractedAnswer", "one week off");
        final Resource fromOther = this.context.create().resource(fromNote.getParent().getPath() + "/other",
            Map.of(TYPE, "sub/Extraction", "jcr:primaryType", "sub:Extraction", "extractedAnswer", "one week off"));
        references(fromOther, "sources", versionOf("other.pdf"));

        this.handler.execute(context(payload(DOCTORS_NOTE), REQUESTER));

        assertNull(resolver().getResource(fromNote.getPath()));
        assertNotNull(resolver().getResource(fromOther.getPath()));
    }

    // Removed mid-parse, the parse lands on nothing and the reading it was for never ends
    @Test
    void refusesWhileTheDocumentsAreBeingRead() throws Exception
    {
        attach(NOTE, "note.pdf");
        modify(this.target, "extractionStatus", "running");

        assertThrows(InvalidStateException.class, () -> this.handler.execute(context(payload(NOTE), REQUESTER)));
        assertEquals(1, documents().size());
    }

    @Test
    void dropsASuggestionNobodyTouchedWithTheFile() throws Exception
    {
        attach(NOTE, "note.pdf");
        final Resource untouched = reading("untouched", versionOf("note.pdf"));
        modify(untouched.getParent(), "value", new String[] {"one week off"});
        modify(untouched, "extractedAnswer", "one week off");
        final String answer = untouched.getParent().getPath();

        this.handler.execute(context(payload(NOTE), REQUESTER));

        assertNull(this.context.resourceResolver().getResource(answer));
    }

    @Test
    void keepsAConfirmedSuggestion() throws Exception
    {
        attach(NOTE, "note.pdf");
        final Resource confirmed = reading("confirmed", versionOf("note.pdf"));
        modify(confirmed.getParent(), "value", new String[] {"one week off"});
        modify(confirmed, "extractedAnswer", "one week off");
        modify(confirmed, "reviewed", Boolean.TRUE);

        this.handler.execute(context(payload(NOTE), REQUESTER));

        assertNull(this.context.resourceResolver().getResource(confirmed.getPath()));
        assertNotNull(this.context.resourceResolver().getResource(confirmed.getParent().getPath()));
    }

    @Test
    void keepsAnAnswerAnotherReadingStillBacks() throws Exception
    {
        attach(NOTE, "note.pdf");
        attach("anything", "other.pdf");
        final Resource fromNote = reading("backed", versionOf("note.pdf"));
        modify(fromNote.getParent(), "value", new String[] {"one week off"});
        modify(fromNote, "extractedAnswer", "one week off");
        final Resource fromOther = this.context.create().resource(fromNote.getParent().getPath() + "/other",
            Map.of(TYPE, "sub/Extraction", "jcr:primaryType", "sub:Extraction", "extractedAnswer", "one week off"));
        references(fromOther, "sources", versionOf("other.pdf"));

        this.handler.execute(context(payload(NOTE), REQUESTER));

        assertNull(this.context.resourceResolver().getResource(fromNote.getPath()));
        assertNotNull(this.context.resourceResolver().getResource(fromOther.getPath()));
    }

    @Test
    void translatesAFailedReadingLookupIntoAPersistenceFailure() throws Exception
    {
        attach(NOTE, "note.pdf");
        final String documentPath = documents().get(0).getPath();
        final Node explosive = Mockito.mock(Node.class, invocation -> {
            throw new RepositoryException("boom");
        });
        final ResourceResolver sabotaged = new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public Resource getResource(final String path)
            {
                final Resource found = super.getResource(path);
                if (found == null || !documentPath.equals(path)) {
                    return found;
                }
                return new ResourceWrapper(found)
                {
                    @Override
                    public Iterable<Resource> getChildren()
                    {
                        final List<Resource> children = new ArrayList<>();
                        super.getChildren().forEach(child -> children.add(new ResourceWrapper(child)
                        {
                            @Override
                            public <T> T adaptTo(final Class<T> type)
                            {
                                return type == Node.class ? type.cast(explosive) : super.adaptTo(type);
                            }
                        }));
                        return children;
                    }
                };
            }
        };

        final PersistenceException failure = assertThrows(PersistenceException.class,
            () -> this.handler.execute(context(payload(NOTE), REQUESTER, sabotaged)));
        assertTrue(failure.getMessage().contains("Could not drop the reading"));
    }

    private void attach(final String requirement, final String fileName) throws Exception
    {
        final Map<String, Object> payload = payload(requirement);
        payload.put(AttachDocumentHandler.FILE_PARAMETER, upload(fileName));
        this.attacher.execute(context(payload, REQUESTER));
        documents();
    }

    private Map<String, Object> payload(final String requirement)
    {
        final Map<String, Object> payload = new HashMap<>();
        payload.put(AttachDocumentHandler.REQUIREMENT_PARAMETER, requirement);
        return payload;
    }

    private EventAttachment upload(final String fileName)
    {
        return new EventAttachment()
        {
            @Override
            public String getFileName()
            {
                return fileName;
            }

            @Override
            public String getMimeType()
            {
                return PDF;
            }

            @Override
            public long getSize()
            {
                return CONTENT.length;
            }

            @Override
            public InputStream openStream()
            {
                return new ByteArrayInputStream(CONTENT);
            }
        };
    }

    /**
     * The submission's documents, after giving each the {@code sling:resourceType} a real repository autocreates
     * and the mock does not.
     */
    private List<Document> documents()
    {
        this.context.resourceResolver().refresh();
        final Resource submission = present(this.context.resourceResolver().getResource(SUBMISSION_PATH));
        submission.getChildren().forEach(child -> {
            if ("sub:Document".equals(child.getValueMap().get("jcr:primaryType", String.class))
                && child.getValueMap().get(TYPE) == null) {
                modify(child, TYPE, Document.RESOURCE_TYPE);
            }
        });
        return present(submission.adaptTo(Submission.class)).getDocuments();
    }

    /** The newest version of the document with this title. */
    private Resource versionOf(final String title)
    {
        for (final Document document : documents()) {
            if (title.equals(document.getTitle())) {
                return present(this.context.resourceResolver().getResource(
                    present(document.getCurrentVersion()).getPath()));
            }
        }
        throw new AssertionError("no document titled " + title);
    }

    /** An answer whose extraction read the given version. */
    private Resource reading(final String name, final Resource version)
    {
        final Resource answer = this.context.create().resource(SUBMISSION_PATH + "/" + name, Map.of(
            TYPE, "sub/Answer", "jcr:primaryType", "sub:Answer"));
        final Resource extraction = this.context.create().resource(answer.getPath() + "/run", Map.of(
            TYPE, "sub/Extraction", "jcr:primaryType", "sub:Extraction"));
        references(extraction, "sources", version);
        return extraction;
    }

    /** Points a multi-valued reference property at one node. */
    private void references(final Resource from, final String property, final Resource to)
    {
        try {
            final Node source = present(from.adaptTo(Node.class));
            source.setProperty(property, new Value[] {
                source.getSession().getValueFactory().createValue(present(to.adaptTo(Node.class))) });
            this.context.resourceResolver().commit();
        } catch (final RepositoryException | PersistenceException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A quote in an extraction, taken from the given version. */
    private void quote(final Resource extraction, final Resource version)
    {
        final Resource evidence = this.context.create().resource(extraction.getPath() + "/q1", Map.of(
            TYPE, "sub/Evidence", "jcr:primaryType", "sub:Evidence", "quote", "one week off"));
        reference(evidence, version.getPath(), "source");
    }

    private <T> T present(final T found)
    {
        assertNotNull(found);
        return found;
    }

    private void reference(final Resource from, final String toPath, final String property)
    {
        try {
            from.adaptTo(Node.class).setProperty(property,
                present(this.context.resourceResolver().getResource(toPath)).adaptTo(Node.class));
            this.context.resourceResolver().commit();
        } catch (final RepositoryException | PersistenceException e) {
            throw new IllegalStateException(e);
        }
    }

    private void modify(final Resource resource, final String property, final Object value)
    {
        try {
            resource.adaptTo(ModifiableValueMap.class).put(property, value);
            this.context.resourceResolver().commit();
        } catch (final PersistenceException e) {
            throw new IllegalStateException(e);
        }
    }

    private WorkflowTaskContext context(final Map<String, Object> payload, final String actor)
    {
        return context(payload, actor, this.context.resourceResolver());
    }

    private WorkflowTaskContext context(final Map<String, Object> payload, final String actor,
        final ResourceResolver resolver)
    {
        final WorkflowEvent event = new WorkflowEvent(DetachDocumentHandler.HANDLER_NAME, payload);
        final Activity activity = Mockito.mock(Activity.class);
        // jcr-mock answers nothing about versioning, so the request reads as checked out
        final Node checkedOut = Mockito.mock(Node.class);
        try {
            Mockito.when(checkedOut.isCheckedOut()).thenReturn(true);
        } catch (final RepositoryException e) {
            throw new IllegalStateException(e);
        }
        final Resource submission = new ResourceWrapper(this.target)
        {
            @Override
            public ResourceResolver getResourceResolver()
            {
                return resolver;
            }

            @Override
            public <A> A adaptTo(final Class<A> type)
            {
                return Node.class.equals(type) ? type.cast(checkedOut) : super.adaptTo(type);
            }
        };
        return new WorkflowTaskContext()
        {
            @Override
            public Resource getTarget()
            {
                return submission;
            }

            @Override
            public String getActor()
            {
                return actor;
            }

            @Override
            public WorkflowEvent getEvent()
            {
                return event;
            }

            @Override
            public Activity getActivity()
            {
                return activity;
            }

            @Override
            public ResourceResolver getResourceResolver()
            {
                return resolver;
            }

            @Override
            public Object getVariable(final String name)
            {
                return null;
            }

            @Override
            public void setVariable(final String name, final Object value)
            {
                throw new IllegalStateException("No variable was expected to be set here");
            }

            @Override
            public void sendEvent(final Resource to, final WorkflowEvent sent)
            {
                throw new IllegalStateException("No event was expected to be sent here");
            }

            @Override
            public void startWorkflow(final Resource host, final WorkflowVersion version)
            {
                throw new IllegalStateException("No workflow was expected to be started here");
            }
        };
    }
}
