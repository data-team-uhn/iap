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

import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.FormRequirement;
import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.submissions.models.Document;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.EventAttachment;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DetachDocumentHandler}: who may take an attachment back off, which requirements they may
 * name, and what is left behind.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class DetachDocumentHandlerTest
{
    private static final String TYPE = "sling:resourceType";

    /** A mock repository has no /libs/sch hierarchy to inherit from, so each requirement carries it itself. */
    private static final String SUPER_TYPE = "sling:resourceSuperType";

    private static final String REQUIREMENT = "sch/Requirement";

    private static final String VERSION_PATH = "/Schemas/timeOffRequest/v1";

    private static final String NOTE_PATH = VERSION_PATH + "/doctorsNote";

    private static final String SUBMISSION_PATH = "/Submissions/ab/cd/ef/aRequest";

    private static final String REQUESTER = "demo-requester";

    private static final String PDF = "application/pdf";

    private static final String NOTE = "note.pdf";

    private static final String DOCTORS_NOTE = "doctorsNote";

    private static final byte[] CONTENT = new byte[] {0x25, 0x50, 0x44, 0x46};

    // JCR-backed rather than the plain mock: what is detached was attached by the real handler, which writes a
    // real REFERENCE and a real binary
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final AttachDocumentHandler attacher = new AttachDocumentHandler();

    private final DetachDocumentHandler handler = new DetachDocumentHandler();

    private Resource target;

    @BeforeEach
    void setUp()
    {
        this.context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, Schema.class,
            SchemaVersion.class, Question.class, FormRequirement.class, DocumentRequirement.class, Document.class,
            Submission.class, Activity.class);
        Tagging.enable(this.context);
        this.context.create().resource("/Schemas/timeOffRequest", Map.of(
            TYPE, Schema.RESOURCE_TYPE, "title", "Time off request", "active", true));
        this.context.create().resource(VERSION_PATH, Map.of(
            TYPE, SchemaVersion.RESOURCE_TYPE, "version", "1.0", "active", true));
        this.context.create().resource(NOTE_PATH, Map.of(
            TYPE, DocumentRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "A note from a doctor",
            "acceptedFileTypes", new String[] {PDF}));
        this.context.create().resource(VERSION_PATH + "/anything", Map.of(
            TYPE, DocumentRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Anything at all"));
        // A requirement nobody uploads a file for, to prove the search is not simply "the child by that name"
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
        assertEquals(DetachDocumentHandler.NAME, this.handler.getName());
    }

    @Test
    void takesTheWholeDocumentOffSoTheRequirementCanBeAnsweredAgain() throws Exception
    {
        attach(NOTE);

        this.handler.execute(context(payload(DOCTORS_NOTE), REQUESTER));

        assertTrue(documents().isEmpty(), "nothing answers the requirement any more");
    }

    // Attaching twice makes versions of one document. Removing is not undoing the last upload - it says the
    // whole attachment was wrong - so every version goes with it.
    @Test
    void takesEveryVersionWithIt() throws Exception
    {
        attach(NOTE);
        attach("note-signed.pdf");

        this.handler.execute(context(payload(DOCTORS_NOTE), REQUESTER));

        assertTrue(documents().isEmpty());
    }

    @Test
    void leavesWhatAnswersSomeOtherRequirementAlone() throws Exception
    {
        attach(NOTE);
        this.attacher.execute(context(attachment("anything", upload("other.pdf")), REQUESTER));
        patchDocumentTypes();

        this.handler.execute(context(payload(DOCTORS_NOTE), REQUESTER));

        final List<Document> left = documents();
        assertEquals(1, left.size());
        assertEquals("other.pdf", left.get(0).getTitle());
    }

    // A reading stores strong references to the revisions it read. Those have to go with the file, or Oak
    // refuses the delete. A reading of some other file stays, and so does an answer the submitter typed.
    @Test
    void dropsTheReadingOfTheFileBeingRemoved() throws Exception
    {
        attach(NOTE);
        this.attacher.execute(context(attachment("anything", upload("other.pdf")), REQUESTER));
        patchDocumentTypes();
        final Resource removed = versionOf(NOTE);
        final Resource kept = versionOf("other.pdf");
        final Resource fromRemoved = reading("from-the-note", removed);
        final String answerPath = fromRemoved.getParent().getPath();
        final Resource quoted = reading("quoted-from-the-note", kept);
        quote(quoted, removed);
        final Resource fromKept = reading("from-the-other", kept);
        quote(fromKept, kept);
        // A child that names no revision, so a missing reference is not treated as a hit
        this.context.create().resource(fromKept.getPath() + "/note", Map.of("jcr:primaryType", "nt:unstructured"));

        this.handler.execute(context(payload(DOCTORS_NOTE), REQUESTER));

        assertNull(resolver().getResource(fromRemoved.getPath()));
        assertNull(resolver().getResource(quoted.getPath()));
        assertNotNull(resolver().getResource(fromKept.getPath()));
        assertNotNull(resolver().getResource(answerPath),
            "the answer stays; only the reading of this file goes");
        assertTrue(documents().stream().noneMatch(document -> NOTE.equals(document.getTitle())));
    }

    @Test
    void translatesAFailedReadingLookupIntoAPersistenceFailure() throws Exception
    {
        attach(NOTE);
        final String documentPath = documents().get(0).getPath();
        final Node explosive = Mockito.mock(Node.class, invocation -> {
            throw new RepositoryException("boom");
        });
        final ResourceResolver sabotaged = new ResourceResolverWrapper(resolver())
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
            () -> this.handler.execute(context(payload(DOCTORS_NOTE), REQUESTER, sabotaged)));
        assertTrue(failure.getMessage().contains("Could not drop the reading"));
    }

    // Who is asking is settled before anything is looked up, so there is nothing to attach first
    @Test
    void refusesAnybodyButTheAuthor()
    {
        final NotAuthorizedException refusal = assertThrows(NotAuthorizedException.class,
            () -> this.handler.execute(context(payload(DOCTORS_NOTE), "somebody-else")));
        assertTrue(refusal.getMessage().contains("Only the person who raised a request"));
    }

    @Test
    void refusesARequestThatHasBeenSent()
    {
        // A document is part of what is being said, so it stops being changeable when the answers do
        modify(this.target, "tags", new String[] {"submitted"});

        final NotAuthorizedException refusal = assertThrows(NotAuthorizedException.class,
            () -> this.handler.execute(context(payload(DOCTORS_NOTE), REQUESTER)));
        assertTrue(refusal.getMessage().contains("can no longer be changed"));
    }

    @Test
    void refusesAnEventThatNamesNoRequirement()
    {
        final InvalidPayloadException refusal = assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(new HashMap<>(), REQUESTER)));
        assertTrue(refusal.getMessage().contains("does not say which attachment"));
    }

    @Test
    void refusesABlankRequirement()
    {
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(payload("  "), REQUESTER)));
    }

    @Test
    void refusesARequirementThisSubmissionDoesNotAskFor()
    {
        final InvalidPayloadException refusal = assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(payload("somethingElse"), REQUESTER)));
        assertTrue(refusal.getMessage().contains("no document requirement"));
    }

    // A form requirement is not something a file answers, so naming one is naming nothing detachable
    @Test
    void refusesARequirementOfTheWrongKind()
    {
        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(payload("details"), REQUESTER)));
    }

    @Test
    void saysSoWhenThereIsNothingAttached()
    {
        final InvalidPayloadException refusal = assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(payload(DOCTORS_NOTE), REQUESTER)));
        assertTrue(refusal.getMessage().contains("nothing to remove"));
    }

    // A document put there by something other than the attach workflow may fulfil nothing at all, and must not
    // be mistaken for the answer to the requirement being removed
    @Test
    void passesOverADocumentThatFulfillsNothing()
    {
        this.context.create().resource(SUBMISSION_PATH + "/stray",
            Map.of(TYPE, Document.RESOURCE_TYPE, "jcr:primaryType", "sub:Document", "title", "stray.pdf"));
        patchDocumentTypes();

        assertThrows(InvalidPayloadException.class,
            () -> this.handler.execute(context(payload(DOCTORS_NOTE), REQUESTER)));
    }

    private void attach(final String fileName) throws Exception
    {
        this.attacher.execute(context(attachment(DOCTORS_NOTE, upload(fileName)), REQUESTER));
        patchDocumentTypes();
    }

    private Map<String, Object> payload(final String requirement)
    {
        final Map<String, Object> payload = new HashMap<>();
        payload.put("requirement", requirement);
        return payload;
    }

    private Map<String, Object> attachment(final String requirement, final EventAttachment file)
    {
        final Map<String, Object> payload = new HashMap<>();
        payload.put("requirement", requirement);
        payload.put("file", file);
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
     * Gives every document its resource type. A real repository autocreates it from the node type; the mock does
     * not, and a document without it is not one the submission model reports - so neither the handler nor the
     * assertions would see anything that was just attached.
     */
    private void patchDocumentTypes()
    {
        this.context.resourceResolver().refresh();
        present(this.context.resourceResolver().getResource(SUBMISSION_PATH)).getChildren().forEach(child -> {
            if ("sub:Document".equals(child.getValueMap().get("jcr:primaryType", String.class))
                && child.getValueMap().get(TYPE) == null) {
                modify(child, TYPE, Document.RESOURCE_TYPE);
            }
        });
    }

    /** The submission's documents, as they stand now. */
    private List<Document> documents()
    {
        patchDocumentTypes();
        return present(present(this.context.resourceResolver().getResource(SUBMISSION_PATH))
            .adaptTo(Submission.class)).getDocuments();
    }

    private <T> T present(final T value)
    {
        assertNotNull(value);
        return value;
    }

    /** The newest revision of the document with this title. */
    private Resource versionOf(final String title)
    {
        for (final Document document : documents()) {
            if (!title.equals(document.getTitle())) {
                continue;
            }
            Resource version = null;
            for (final Resource child : present(resolver().getResource(document.getPath())).getChildren()) {
                if ("sub:DocumentVersion".equals(child.getValueMap().get("jcr:primaryType", String.class))) {
                    version = child;
                }
            }
            return present(version);
        }
        throw new AssertionError("no document titled " + title);
    }

    /** An extraction whose sources point at one revision. */
    private Resource reading(final String name, final Resource version)
    {
        final Resource answer = this.context.create().resource(SUBMISSION_PATH + "/" + name, Map.of(
            TYPE, "sub/Answer", "jcr:primaryType", "sub:Answer"));
        final Resource extraction = this.context.create().resource(answer.getPath() + "/run", Map.of(
            TYPE, "sub/Extraction", "jcr:primaryType", "sub:Extraction"));
        references(extraction, "sources", version);
        return extraction;
    }

    /** A quote on an extraction, naming the revision it came from. */
    private void quote(final Resource extraction, final Resource version)
    {
        final Resource evidence = this.context.create().resource(extraction.getPath() + "/q1", Map.of(
            TYPE, "sub/Evidence", "jcr:primaryType", "sub:Evidence", "quote", "one week off"));
        reference(evidence, version.getPath(), "source");
    }

    /** A multi-valued REFERENCE, which is how an extraction points at the revisions it read. */
    private void references(final Resource from, final String property, final Resource to)
    {
        try {
            final Node source = present(from.adaptTo(Node.class));
            final Value[] values = new Value[] { source.getSession().getValueFactory().createValue(
                present(to.adaptTo(Node.class))) };
            source.setProperty(property, values);
            resolver().commit();
        } catch (final RepositoryException | PersistenceException e) {
            throw new IllegalStateException(e);
        }
    }

    private ResourceResolver resolver()
    {
        return this.context.resourceResolver();
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
        return context(payload, actor, resolver());
    }

    private WorkflowTaskContext context(final Map<String, Object> payload, final String actor,
        final ResourceResolver resolver)
    {
        final WorkflowEvent event = new WorkflowEvent("detachDocument", payload);
        final Map<String, Object> variables = new HashMap<>();
        final Activity activity = Mockito.mock(Activity.class);
        final Resource submission = new ResourceWrapper(this.target)
        {
            @Override
            public ResourceResolver getResourceResolver()
            {
                return resolver;
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
        };
    }
}
