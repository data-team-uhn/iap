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
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.Session;
import javax.jcr.Workspace;
import javax.jcr.version.VersionManager;

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
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.Document;
import io.uhndata.iap.submissions.models.DocumentVersion;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AttachDocumentHandler}: who may attach a file, which requirements they may attach it to,
 * and what ends up stored.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class AttachDocumentHandlerTest
{
    private static final String TYPE = "sling:resourceType";

    // A mock repository has no /libs/sch hierarchy to inherit from, so each requirement has to carry the super
    // type itself or the version reports no requirements at all
    private static final String SUPER_TYPE = "sling:resourceSuperType";

    private static final String REQUIREMENT = "sch/Requirement";

    private static final String VERSION_PATH = "/Schemas/timeOffRequest/v1";

    private static final String NOTE = "doctorsNote";

    private static final String NOTE_PATH = VERSION_PATH + "/" + NOTE;

    private static final String SUBMISSION_PATH = "/Submissions/ab/cd/ef/aRequest";

    private static final String REQUESTER = "demo-requester";

    private static final String PDF = "application/pdf";

    private static final byte[] CONTENT = new byte[] {0x25, 0x50, 0x44, 0x46};

    private static final Map<String, String> RESOURCE_TYPES = Map.of("sub:Document", Document.RESOURCE_TYPE,
        "sub:DocumentVersion", DocumentVersion.RESOURCE_TYPE, "sub:File", File.RESOURCE_TYPE);

    // JCR-backed rather than the plain mock: the handler writes a real REFERENCE and a real binary
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final AttachDocumentHandler handler = new AttachDocumentHandler();

    private Resource target;

    // What the request reads as when adapted to a node: jcr-mock answers nothing about versioning, so each test says
    // whether the request is checked in
    private Node node;

    @BeforeEach
    void setUp() throws RepositoryException
    {
        this.node = Mockito.mock(Node.class);
        Mockito.when(this.node.isCheckedOut()).thenReturn(true);
        this.context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, Schema.class,
            SchemaVersion.class, Question.class, FormRequirement.class, DocumentRequirement.class, Document.class,
            DocumentVersion.class, File.class, Submission.class, Activity.class, Answer.class, Extraction.class);
        // Whether a request may still be changed is read from its lifecycle tag
        Tagging.enable(this.context);
        this.context.create().resource("/Schemas/timeOffRequest", Map.of(
            TYPE, Schema.RESOURCE_TYPE, "title", "Time off request"));
        this.context.create().resource(VERSION_PATH, Map.of(
            TYPE, SchemaVersion.RESOURCE_TYPE, "version", "1.0", "tags", new String[] {"active"}));
        this.context.create().resource(NOTE_PATH, Map.of(
            TYPE, DocumentRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Doctor's note",
            "acceptedFileTypes", new String[] {PDF}));
        this.context.create().resource(VERSION_PATH + "/anything", Map.of(
            TYPE, DocumentRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Anything at all"));
        // A requirement of a kind nobody uploads a file for, to prove the search is not simply "the child by
        // that name"
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
        assertEquals(AttachDocumentHandler.HANDLER_NAME, this.handler.getName());
    }

    @Test
    void storesTheFileAsADocumentFulfillingTheRequirementItAnswers() throws Exception
    {
        this.handler.execute(context(payload(NOTE, upload("note.pdf", PDF))));

        final Resource document = onlyDocument();
        assertEquals("note.pdf", document.getValueMap().get("title", String.class));
        // A real REFERENCE, holding the requirement node's own identifier
        assertEquals(identifierOf(NOTE_PATH), document.getValueMap().get("fulfills", String.class));
    }

    @Test
    void storesTheContentAsTheDocumentsFirstVersion() throws Exception
    {
        this.handler.execute(context(payload(NOTE, upload("note.pdf", PDF))));

        assertEquals(1, onlyDocument().adaptTo(Document.class).getVersions().size());
        final Resource file = uploadedFile();
        assertEquals("nt:file", file.getValueMap().get("jcr:primaryType", String.class));
        final Resource content = child(file, "jcr:content");
        assertEquals(PDF, content.getValueMap().get("jcr:mimeType", String.class));
        assertArrayEquals(CONTENT, content.getValueMap().get("jcr:data", InputStream.class).readAllBytes());
    }

    @Test
    void addsAnotherUploadForTheSameRequirementAsANewVersion() throws Exception
    {
        this.handler.execute(context(payload(NOTE, upload("wrong.pdf", PDF))));
        onlyDocument();
        this.handler.execute(context(payload(NOTE, upload("right.pdf", PDF))));

        final Resource document = onlyDocument();
        assertEquals("right.pdf", document.getValueMap().get("title", String.class));
        assertEquals(2, document.adaptTo(Document.class).getVersions().size());
        assertNotNull(uploadedFile());
    }

    // A reading leaves answered questions alone, so what the old file said has to go for the new one to be read
    @Test
    void dropsTheUntouchedSuggestionsOfTheFileItReplaces() throws Exception
    {
        this.handler.execute(context(payload(NOTE, upload("note.pdf", PDF))));
        final Resource first = present(this.context.resourceResolver().getResource(
            present(onlyDocument().adaptTo(Document.class).getCurrentVersion()).getPath()));
        final String untouched = reading("untouched", first, "one week off", "one week off");
        final String changed = reading("changed", first, "two weeks off", "one week off");

        this.handler.execute(context(payload(NOTE, upload("note-signed.pdf", PDF))));

        assertNull(this.context.resourceResolver().getResource(untouched), "the new file is read for it");
        assertNotNull(this.context.resourceResolver().getResource(changed), "what the submitter wrote stays");
        assertNotNull(this.context.resourceResolver().getResource(changed + "/run"), "with its reading");
    }

    /** An answer read from a version: the value it holds now, and what the model suggested. */
    private String reading(final String name, final Resource version, final String value, final String suggested)
        throws Exception
    {
        final Resource answer = this.context.create().resource(SUBMISSION_PATH + "/" + name, Map.of(
            TYPE, "sub/Answer", "jcr:primaryType", "sub:Answer", "value", new String[] {value}));
        final Resource extraction = this.context.create().resource(answer.getPath() + "/run", Map.of(
            TYPE, "sub/Extraction", "jcr:primaryType", "sub:Extraction", "extractedAnswer", suggested));
        final Node run = extraction.adaptTo(Node.class);
        run.setProperty("sources", new Value[] {
            run.getSession().getValueFactory().createValue(version.adaptTo(Node.class)) });
        this.context.resourceResolver().commit();
        return answer.getPath();
    }

    @Test
    void keepsTheDocumentsOfDifferentRequirementsApart() throws Exception
    {
        this.handler.execute(context(payload(NOTE, upload("note.pdf", PDF))));
        documents();
        this.handler.execute(context(payload("anything", upload("scan.png", "image/png"))));

        assertEquals(2, documents().size());
    }

    @Test
    void takesARequirementNamedByItsFullPath() throws Exception
    {
        // What the UI has to hand is the path it rendered the requirement from, so both spellings work
        this.handler.execute(context(payload(NOTE_PATH, upload("note.pdf", PDF))));

        assertEquals(identifierOf(NOTE_PATH), onlyDocument().getValueMap().get("fulfills", String.class));
    }

    @Test
    void callsAnUnnamedUploadSomething() throws Exception
    {
        this.handler.execute(context(payload("anything", upload(null, PDF))));

        assertEquals("Attachment", onlyDocument().getValueMap().get("title", String.class));
        assertNotNull(uploadedFile());
    }

    @Test
    void recordsAnUndeclaredTypeAsTheGenericOne() throws Exception
    {
        // The repository has to serve the file back with some type, and a wrong guess is worse than an honest
        // "bytes"
        this.handler.execute(context(payload("anything", upload("notes", null))));

        assertEquals("application/octet-stream",
            child(uploadedFile(), "jcr:content").getValueMap().get("jcr:mimeType", String.class));
    }

    @Test
    void keepsAFileNameThatIsNoJcrName() throws Exception
    {
        // The name is only ever the title, so nothing in it can make the upload fail
        this.handler.execute(context(payload("anything", upload("scan: page [1]/2\t.pdf", PDF))));

        assertEquals("scan: page [1]/2\t.pdf", onlyDocument().getValueMap().get("title", String.class));
        assertNotNull(uploadedFile());
    }

    @Test
    void acceptsAnyTypeForARequirementThatNamesNone()
        throws Exception
    {
        // A requirement that has not said what it wants is not one that wants nothing
        this.handler.execute(context(payload("anything", upload("scan.png", "image/png"))));

        assertNotNull(uploadedFile());
    }

    @Test
    void refusesATypeTheRequirementDoesNotAccept()
    {
        final InvalidPayloadException failure = assertThrows(InvalidPayloadException.class, () -> this.handler
            .execute(context(payload(NOTE, upload("note.png", "image/png")))));

        // Named, because a refusal that does not say what would have been accepted cannot be acted on
        assertTrue(failure.getMessage().contains(PDF));
        assertTrue(failure.getMessage().contains("Doctor's note"));
    }

    @Test
    void refusesAFileOfNoDeclaredTypeWhereTheTypeIsRestricted()
    {
        final InvalidPayloadException failure = assertThrows(InvalidPayloadException.class, () -> this.handler
            .execute(context(payload(NOTE, upload("note", null)))));

        assertTrue(failure.getMessage().contains("no declared type"));
    }

    @Test
    void comparesTypesWithoutCaseOrParameters() throws Exception
    {
        this.handler.execute(context(payload(NOTE, upload("note.pdf", "Application/PDF; charset=binary"))));

        // Stored the way it was compared
        assertEquals(PDF, child(uploadedFile(), "jcr:content").getValueMap().get("jcr:mimeType", String.class));
    }

    @Test
    void readsTheTypeFromTheExtensionWhenTheBrowserSentAGenericOne() throws Exception
    {
        this.handler.execute(context(payload(NOTE, upload("Note.PDF", "application/octet-stream"))));

        assertEquals(PDF, child(uploadedFile(), "jcr:content").getValueMap().get("jcr:mimeType", String.class));
    }

    @Test
    void readsTheTypeFromTheExtensionWhenTheBrowserSentNone() throws Exception
    {
        this.handler.execute(context(payload(NOTE, upload("note.pdf", null))));

        assertEquals(PDF, child(uploadedFile(), "jcr:content").getValueMap().get("jcr:mimeType", String.class));
    }

    @Test
    void refusesAGenericTypeWhoseExtensionSaysNothing()
    {
        final InvalidPayloadException failure = assertThrows(InvalidPayloadException.class, () -> this.handler
            .execute(context(payload(NOTE, upload("scan.bin", "application/octet-stream")))));

        assertTrue(failure.getMessage().contains("application/octet-stream"));
    }

    @Test
    void refusesAFileOverTheLimitWithoutReadingIt()
    {
        final InvalidPayloadException failure = assertThrows(InvalidPayloadException.class, () -> this.handler
            .execute(context(payload(NOTE, sized(AttachDocumentHandler.MAX_FILE_BYTES + 1)))));

        assertEquals("That file is 51 MB, and the limit is 50 MB", failure.getMessage());
    }

    @Test
    void takesAFileExactlyAtTheLimit() throws Exception
    {
        this.handler.execute(context(payload(NOTE, sized(AttachDocumentHandler.MAX_FILE_BYTES))));

        assertNotNull(uploadedFile());
    }

    @Test
    void checksOutTheRequestBeforeAttachingToIt() throws Exception
    {
        // The Sling POST servlet checks in whatever it creates, and a checked-in request takes no documents
        final Session session = Mockito.mock(Session.class);
        final Workspace workspace = Mockito.mock(Workspace.class);
        final VersionManager versions = Mockito.mock(VersionManager.class);
        Mockito.when(this.node.isCheckedOut()).thenReturn(false);
        Mockito.when(this.node.isNodeType("mix:versionable")).thenReturn(true);
        Mockito.when(this.node.getPath()).thenReturn(SUBMISSION_PATH);
        Mockito.when(this.node.getSession()).thenReturn(session);
        Mockito.when(session.getWorkspace()).thenReturn(workspace);
        Mockito.when(workspace.getVersionManager()).thenReturn(versions);
        Mockito.doAnswer(invocation -> {
            assertFalse(this.target.hasChildren(), "A document was written before the request was checked out");
            return null;
        }).when(versions).checkout(SUBMISSION_PATH);

        this.handler.execute(context(payload(NOTE, upload("note.pdf", PDF))));

        Mockito.verify(versions).checkout(SUBMISSION_PATH);
    }

    @Test
    void refusesSomebodyElsesRequest()
    {
        assertThrows(NotAuthorizedException.class, () -> this.handler.execute(
            context(payload(NOTE, upload("note.pdf", PDF)), "somebody-else")));
    }

    @Test
    void refusesARequestThatHasAlreadyBeenSent()
    {
        // A document is part of what is being said, so it stops being changeable when the answers do
        modify(this.target, "tags", new String[] {"submitted"});

        assertThrows(InvalidStateException.class, () -> this.handler.execute(
            context(payload(NOTE, upload("note.pdf", PDF)))));
    }

    @Test
    void refusesAnEventCarryingNoFile()
    {
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(Map.of(AttachDocumentHandler.REQUIREMENT_PARAMETER, NOTE,
                AttachDocumentHandler.FILE_PARAMETER, "note.pdf"))));
    }

    @Test
    void refusesAnEventThatDoesNotSayWhatTheFileIsFor()
    {
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(Map.of(AttachDocumentHandler.FILE_PARAMETER, upload("note.pdf", PDF)))));
    }

    @Test
    void refusesABlankRequirementName()
    {
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(payload("  ", upload("note.pdf", PDF)))));
    }

    @Test
    void refusesARequirementThisRequestDoesNotAskFor()
    {
        // Resolved through the schema rather than by trusting the path, or a caller could attach a document that
        // nothing on this submission ever asked for
        assertThrows(InvalidPayloadException.class, () -> this.handler.execute(
            context(payload("/Schemas/somethingElse/v1/aNote", upload("note.pdf", PDF)))));
    }

    @Test
    void refusesARequirementWhoseConditionDoesNotHold()
    {
        final ConditionEvaluator conditions = Mockito.mock(ConditionEvaluator.class);
        Mockito.when(conditions.applies(Mockito.any(), Mockito.any())).thenAnswer(invocation ->
            !(invocation.getArgument(0) instanceof DocumentRequirement requirement)
                || !NOTE_PATH.equals(requirement.getPath()));
        this.context.registerService(ConditionEvaluator.class, conditions);

        assertThrows(InvalidPayloadException.class, () -> this.handler.execute(
            context(payload(NOTE, upload("note.pdf", PDF)))));
    }

    @Test
    void refusesARequirementThatIsNotAskingForAFile()
    {
        // The form requirement is a child of the same version under a perfectly ordinary name
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(payload("details", upload("note.pdf", PDF)))));
    }

    @Test
    void translatesAFailedReferenceIntoAPersistenceFailure()
    {
        // The document is created but cannot be adapted to a node, so what it fulfills cannot be recorded. That has
        // to reach the engine as a persistence problem it knows how to translate rather than as a raw repository
        // error escaping a handler. Sabotaged at creation because that is the one resource the handler obtains
        // through the resolver it was handed
        final Node explosive = Mockito.mock(Node.class, invocation -> {
            throw new RepositoryException("boom");
        });
        final ResourceResolver sabotaged = new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public Resource create(final Resource parent, final String name, final Map<String, Object> properties)
                throws PersistenceException
            {
                return new ResourceWrapper(super.create(parent, name, properties))
                {
                    @Override
                    public <T> T adaptTo(final Class<T> type)
                    {
                        return type == Node.class ? type.cast(explosive) : super.adaptTo(type);
                    }
                };
            }
        };

        final PersistenceException failure = assertThrows(PersistenceException.class, () -> this.handler.execute(
            context(payload(NOTE, upload("note.pdf", PDF)), REQUESTER, sabotaged)));
        assertTrue(failure.getMessage().contains("Cannot point fulfills"));
    }

    @Test
    void translatesAnUnreadableUploadIntoAPersistenceFailure()
    {
        // A stream that dies halfway is the ordinary way an upload fails, and the document node is already there
        // by then. The engine's own rollback is what undoes it, so all this owes is a failure it recognises
        final EventAttachment broken = new EventAttachment()
        {
            @Override
            public String getFileName()
            {
                return "note.pdf";
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
            public InputStream openStream() throws IOException
            {
                throw new IOException("the connection went away");
            }
        };

        final PersistenceException failure = assertThrows(PersistenceException.class,
            () -> this.handler.execute(context(payload(NOTE, broken))));
        assertTrue(failure.getMessage().contains("Could not store"));
    }

    @Test
    void refusesARequirementThatCannotBeReadBack()
    {
        // Its own model was built from the schema, so what can go wrong is not that it is missing but that the
        // session doing the writing cannot see it
        final ResourceResolver blind = new ResourceResolverWrapper(this.context.resourceResolver())
        {
            @Override
            public Resource getResource(final String path)
            {
                return NOTE_PATH.equals(path) ? null : super.getResource(path);
            }
        };

        final PersistenceException failure = assertThrows(PersistenceException.class, () -> this.handler.execute(
            context(payload(NOTE, upload("note.pdf", PDF)), REQUESTER, blind)));
        assertTrue(failure.getMessage().contains("Could not read"));
    }

    private Map<String, Object> payload(final String requirement, final Object file)
    {
        final Map<String, Object> payload = new HashMap<>();
        payload.put(AttachDocumentHandler.REQUIREMENT_PARAMETER, requirement);
        payload.put(AttachDocumentHandler.FILE_PARAMETER, file);
        return payload;
    }

    private EventAttachment upload(final String fileName, final String mimeType)
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
                return mimeType;
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
     * A PDF claiming the given size. Over the limit, its content must never be read.
     */
    private EventAttachment sized(final long size)
    {
        return new EventAttachment()
        {
            @Override
            public String getFileName()
            {
                return "huge.pdf";
            }

            @Override
            public String getMimeType()
            {
                return PDF;
            }

            @Override
            public long getSize()
            {
                return size;
            }

            @Override
            public InputStream openStream()
            {
                if (size > AttachDocumentHandler.MAX_FILE_BYTES) {
                    throw new AssertionError("A file over the limit was read");
                }
                return new ByteArrayInputStream(CONTENT);
            }
        };
    }

    /**
     * The one document the handler created, with the {@code sling:resourceType} a real repository autocreates from
     * the node type and a mock repository does not — the runtime cannot stamp it itself, the property is protected.
     */
    private Resource onlyDocument()
    {
        final List<Document> documents = documents();
        assertEquals(1, documents.size());
        return present(this.context.resourceResolver().getResource(documents.get(0).getPath()));
    }

    private List<Document> documents()
    {
        this.context.resourceResolver().refresh();
        stampResourceTypes(present(this.context.resourceResolver().getResource(SUBMISSION_PATH)));
        return submission().getDocuments();
    }

    /**
     * The upload stored in the current version of the one document.
     */
    private Resource uploadedFile()
    {
        final DocumentVersion version = present(onlyDocument().adaptTo(Document.class).getCurrentVersion());
        return present(present(version.getFile()).getUploadedFile());
    }

    private void stampResourceTypes(final Resource resource)
    {
        final String type = RESOURCE_TYPES.get(resource.getValueMap().get("jcr:primaryType", String.class));
        if (type != null && resource.getValueMap().get(TYPE) == null) {
            modify(resource, TYPE, type);
        }
        resource.getChildren().forEach(this::stampResourceTypes);
    }

    private Submission submission()
    {
        this.context.resourceResolver().refresh();
        return present(this.context.resourceResolver().getResource(SUBMISSION_PATH)).adaptTo(Submission.class);
    }

    private Resource child(final Resource parent, final String name)
    {
        return present(parent.getChild(name));
    }

    private <T> T present(final T found)
    {
        assertNotNull(found);
        return found;
    }

    private String identifierOf(final String path)
    {
        try {
            return present(this.context.resourceResolver().getResource(path)).adaptTo(Node.class).getIdentifier();
        } catch (final RepositoryException e) {
            throw new IllegalStateException(e);
        }
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

    private WorkflowTaskContext context(final Map<String, Object> payload)
    {
        return context(payload, REQUESTER);
    }

    private WorkflowTaskContext context(final Map<String, Object> payload, final String actor)
    {
        return context(payload, actor, this.context.resourceResolver());
    }

    private WorkflowTaskContext context(final Map<String, Object> payload, final String actor,
        final ResourceResolver resolver)
    {
        final WorkflowEvent event = new WorkflowEvent("attachDocument", payload);
        final Map<String, Object> variables = new HashMap<>();
        final Activity activity = Mockito.mock(Activity.class);
        final Node asNode = this.node;
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
                return Node.class.equals(type) ? type.cast(asNode) : super.adaptTo(type);
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
                return variables.get(name);
            }

            @Override
            public void setVariable(final String name, final Object value)
            {
                variables.put(name, value);
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
