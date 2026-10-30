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
package io.uhndata.iap.submissions.internal.handlers;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceWrapper;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.conditions.api.ConditionEvaluator;
import io.uhndata.iap.content.models.Content;
import io.uhndata.iap.documents.generation.api.DocumentGenerator;
import io.uhndata.iap.documents.generation.api.GeneratedDocument;
import io.uhndata.iap.documents.generation.api.GenerationResult;
import io.uhndata.iap.documents.generation.api.GenerationResult.Status;
import io.uhndata.iap.entities.models.Entity;
import io.uhndata.iap.entities.models.EntityPart;
import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.submissions.internal.Referenceables;
import io.uhndata.iap.submissions.internal.Tagging;
import io.uhndata.iap.submissions.models.Document;
import io.uhndata.iap.submissions.models.DocumentVersion;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.NotAuthorizedException;
import io.uhndata.iap.workflows.api.WorkflowDefinitionException;
import io.uhndata.iap.workflows.api.WorkflowEvent;
import io.uhndata.iap.workflows.api.WorkflowFailedException;
import io.uhndata.iap.workflows.models.Activity;
import io.uhndata.iap.workflows.models.WorkflowVersion;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GenerateDocumentHandler}: what it stores, where, and that a generation that did not work
 * leaves nothing behind that could pass for the requirement's document.
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class GenerateDocumentHandlerTest
{
    private static final String TYPE = "sling:resourceType";

    // See AttachDocumentHandlerTest: a mock repository has no /libs/sch hierarchy to inherit from
    private static final String SUPER_TYPE = "sling:resourceSuperType";

    private static final String REQUIREMENT = "sch/Requirement";

    private static final String VERSION_PATH = "/Schemas/ethics/v1";

    private static final String LETTER = "decisionLetter";

    private static final String LETTER_PATH = VERSION_PATH + "/" + LETTER;

    private static final String SUBMISSION_PATH = "/Submissions/ab/cd/ef/aStudy";

    private static final String REQUESTER = "demo-researcher";

    private static final byte[] DOCX = { 0x50, 0x4B, 1 };

    private static final byte[] PDF = { 0x25, 0x50, 0x44, 0x46 };

    private static final Map<String, String> RESOURCE_TYPES = Map.of("sub:Document", Document.RESOURCE_TYPE,
        "sub:DocumentVersion", DocumentVersion.RESOURCE_TYPE, "sub:File", File.RESOURCE_TYPE);

    // JCR-backed, as for attaching: the handler writes a real REFERENCE and real binaries
    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_MOCK);

    private final DocumentGenerator generator = Mockito.mock(DocumentGenerator.class);

    private final GenerateDocumentHandler handler = new GenerateDocumentHandler(this.generator);

    private final Map<String, Object> variables = new HashMap<>();

    private Resource target;

    private Node node;

    @BeforeEach
    void setUp() throws RepositoryException, PersistenceException
    {
        this.node = Mockito.mock(Node.class);
        Mockito.when(this.node.isCheckedOut()).thenReturn(true);
        this.context.addModelsForClasses(Content.class, Entity.class, EntityPart.class, Schema.class,
            SchemaVersion.class, DocumentRequirement.class, Document.class, DocumentVersion.class, File.class,
            Submission.class, Activity.class);
        Tagging.enable(this.context);
        this.context.create().resource("/Schemas/ethics", Map.of(TYPE, Schema.RESOURCE_TYPE, "title", "Ethics"));
        this.context.create().resource(VERSION_PATH, Map.of(
            TYPE, SchemaVersion.RESOURCE_TYPE, "version", "1.0", "tags", new String[] { "active" }));
        this.context.create().resource(LETTER_PATH, Map.of(
            TYPE, DocumentRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Decision letter",
            "generated", true));
        this.context.load().binaryFile(new ByteArrayInputStream(DOCX), LETTER_PATH + "/template",
            GeneratedDocument.DOCX_MIME_TYPE);
        this.context.create().resource(VERSION_PATH + "/consent", Map.of(
            TYPE, DocumentRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Consent form"));
        this.context.create().resource(VERSION_PATH + "/untemplated", Map.of(
            TYPE, DocumentRequirement.RESOURCE_TYPE, SUPER_TYPE, REQUIREMENT, "label", "Untemplated letter",
            "generated", true));
        Referenceables.identify(this.context, LETTER_PATH, VERSION_PATH + "/consent", VERSION_PATH + "/untemplated");
        this.target = this.context.create().resource(SUBMISSION_PATH, Map.of(
            TYPE, Submission.RESOURCE_TYPE, "title", "A study", "createdBy", REQUESTER,
            "tags", new String[] { "draft" }));
        reference(this.target, VERSION_PATH, "schemaVersion");
        generatorAnswers(GenerationResult.generated(new GeneratedDocument(DOCX, PDF)));
    }

    @Test
    void hasItsAdvertisedName()
    {
        assertEquals(GenerateDocumentHandler.HANDLER_NAME, this.handler.getName());
    }

    @Test
    void storesThePdfAsTheRequirementsDocumentWithTheDocxBesideIt() throws Exception
    {
        this.handler.execute(context(LETTER));

        final Resource document = onlyDocument();
        assertEquals("Decision letter.pdf", document.getValueMap().get("title", String.class));
        assertEquals(identifierOf(LETTER_PATH), document.getValueMap().get("fulfills", String.class));
        final Resource file = currentFile();
        assertArrayEquals(PDF, bytes(file.getChild("uploadedFile")));
        assertEquals("application/pdf",
            file.getChild("uploadedFile").getValueMap().get("jcr:content/jcr:mimeType", String.class));
        assertArrayEquals(DOCX, bytes(file.getChild(File.GENERATED_DOCX_CHILD)));
    }

    @Test
    void fillsInTheRequirementsOwnTemplateForThisSubmission() throws Exception
    {
        this.handler.execute(context(LETTER));

        Mockito.verify(this.generator).generate(
            Mockito.argThat(template -> (LETTER_PATH + "/template").equals(template.getPath())),
            Mockito.argThat(submission -> SUBMISSION_PATH.equals(submission.getPath())));
    }

    @Test
    void leavesThePdfsPathForTheNodesDownstream() throws Exception
    {
        this.handler.execute(context(LETTER));

        assertEquals(currentFile().getPath() + "/uploadedFile",
            this.variables.get(GenerateDocumentHandler.GENERATED_PATH_VARIABLE));
    }

    @Test
    void generatingAgainAddsAVersionRatherThanReplacingTheOneShown() throws Exception
    {
        this.handler.execute(context(LETTER));
        // Stamped in between, as a real repository would have, or the second run cannot see the first document
        onlyDocument();
        this.handler.execute(context(LETTER));

        assertEquals(2, onlyDocument().adaptTo(Document.class).getVersions().size());
    }

    @Test
    void namesTheRequirementByPathAsWellAsByName() throws Exception
    {
        this.handler.execute(context(LETTER_PATH));

        onlyDocument();
    }

    @Test
    void generatesNothingForARequirementThisSubmissionIsNotAsked() throws Exception
    {
        final ConditionEvaluator conditions = Mockito.mock(ConditionEvaluator.class);
        Mockito.when(conditions.applies(Mockito.any(), Mockito.any())).thenReturn(false);
        this.context.registerService(ConditionEvaluator.class, conditions);

        this.handler.execute(context(LETTER));

        assertTrue(documents().isEmpty());
        Mockito.verifyNoInteractions(this.generator);
    }

    @Test
    void aGenerationThatDidNotWorkFailsTheStepWithItsReasonAndStoresNothing()
    {
        generatorAnswers(GenerationResult.failed(Status.TEMPLATE_ERROR, "'decision' is undefined"));

        final WorkflowFailedException failure = assertThrows(WorkflowFailedException.class,
            () -> this.handler.execute(context(LETTER)));

        assertTrue(failure.getMessage().contains("Decision letter could not be generated: 'decision' is undefined"),
            failure.getMessage());
        assertTrue(documents().isEmpty(), "nothing may look like the requirement's document");
    }

    @Test
    void aBusyGeneratorAsksForAnotherTryRatherThanBlamingTheTemplate()
    {
        generatorAnswers(GenerationResult.failed(Status.UNAVAILABLE, "The generation worker answered 503"));

        final WorkflowFailedException failure = assertThrows(WorkflowFailedException.class,
            () -> this.handler.execute(context(LETTER)));

        assertTrue(failure.getMessage().contains("Try again in a moment"), failure.getMessage());
        assertTrue(documents().isEmpty(), "nothing may look like the requirement's document");
    }

    @Test
    void aGeneratedRequirementWithoutATemplateFailsTheStep()
    {
        assertThrows(WorkflowFailedException.class, () -> this.handler.execute(context("untemplated")));
        Mockito.verifyNoInteractions(this.generator);
    }

    @Test
    void anActivityWhoseRequirementIsNotANameIsADefinitionError()
    {
        assertThrows(WorkflowDefinitionException.class, () -> this.handler.execute(context(" ")));
        assertThrows(WorkflowDefinitionException.class, () -> this.handler.execute(context(Boolean.TRUE)));
    }

    // Until submitting is a workflow step (IAP-88): the submitter asks for the document from the form

    @Test
    void generatesTheDocumentTheSubmitterAsksFor() throws Exception
    {
        this.handler.execute(asked(LETTER, REQUESTER));

        assertArrayEquals(PDF, bytes(currentFile().getChild("uploadedFile")));
    }

    @Test
    void refusesToGenerateForSomebodyElsesRequest()
    {
        assertThrows(NotAuthorizedException.class, () -> this.handler.execute(asked(LETTER, "somebody-else")));
        Mockito.verifyNoInteractions(this.generator);
    }

    @Test
    void refusesToGenerateForARequestThatHasAlreadyBeenSent() throws PersistenceException
    {
        this.target.adaptTo(ModifiableValueMap.class).put("tags", new String[] { "submitted" });
        this.context.resourceResolver().commit();

        assertThrows(InvalidStateException.class, () -> this.handler.execute(asked(LETTER, REQUESTER)));
        Mockito.verifyNoInteractions(this.generator);
    }

    @Test
    void refusesToGenerateAnythingButAGeneratedDocumentOfThisRequest()
    {
        assertThrows(InvalidPayloadException.class, () -> this.handler.execute(context(null)));
        assertThrows(InvalidPayloadException.class, () -> this.handler.execute(asked("consent", REQUESTER)));
        assertThrows(InvalidPayloadException.class, () -> this.handler.execute(asked("noSuchLetter", REQUESTER)));
        Mockito.verifyNoInteractions(this.generator);
    }

    @Test
    void anActivityNamingARequirementThisSchemaDoesNotHaveIsADefinitionError()
    {
        assertThrows(WorkflowDefinitionException.class, () -> this.handler.execute(context("noSuchLetter")));
    }

    @Test
    void anActivityNamingADocumentTheSubmitterUploadsIsADefinitionError()
    {
        final WorkflowDefinitionException failure = assertThrows(WorkflowDefinitionException.class,
            () -> this.handler.execute(context("consent")));

        assertTrue(failure.getMessage().contains("Consent form, which is not generated"), failure.getMessage());
    }

    private void generatorAnswers(final GenerationResult result)
    {
        Mockito.when(this.generator.generate(Mockito.any(), Mockito.any())).thenReturn(result);
    }

    // An activity naming the requirement, as a schema's own workflow does
    private WorkflowTaskContext context(final Object requirement)
    {
        return context(requirement, Map.of(), REQUESTER);
    }

    // An activity naming none, and an event from the form that does
    private WorkflowTaskContext asked(final String requirement, final String actor)
    {
        return context(null, Map.of(GenerateDocumentHandler.REQUIREMENT_PARAMETER, requirement), actor);
    }

    private WorkflowTaskContext context(final Object requirement, final Map<String, Object> payload,
        final String actor)
    {
        final ResourceResolver resolver = this.context.resourceResolver();
        final WorkflowEvent event = new WorkflowEvent("generateDocument", payload);
        final Activity activity = Mockito.mock(Activity.class);
        Mockito.when(activity.getPath()).thenReturn("/Workflows/ethics/v1/generate");
        Mockito.when(activity.get(GenerateDocumentHandler.REQUIREMENT_PARAMETER)).thenReturn(requirement);
        final Node asNode = this.node;
        final Resource submission = new ResourceWrapper(this.target)
        {
            @Override
            public <A> A adaptTo(final Class<A> type)
            {
                return Node.class.equals(type) ? type.cast(asNode) : super.adaptTo(type);
            }
        };
        final Map<String, Object> shared = this.variables;
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
                return shared.get(name);
            }

            @Override
            public void setVariable(final String name, final Object value)
            {
                shared.put(name, value);
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

    private Resource onlyDocument()
    {
        final List<Document> documents = documents();
        assertEquals(1, documents.size());
        return present(this.context.resourceResolver().getResource(documents.get(0).getPath()));
    }

    private Resource currentFile()
    {
        final DocumentVersion version = present(onlyDocument().adaptTo(Document.class).getCurrentVersion());
        return present(this.context.resourceResolver().getResource(present(version.getFile()).getPath()));
    }

    private List<Document> documents()
    {
        this.context.resourceResolver().refresh();
        stampResourceTypes(present(this.context.resourceResolver().getResource(SUBMISSION_PATH)));
        this.context.resourceResolver().refresh();
        return present(this.context.resourceResolver().getResource(SUBMISSION_PATH)).adaptTo(Submission.class)
            .getDocuments();
    }

    // A real repository autocreates these from the node type; a mock one does not, see AttachDocumentHandlerTest
    private void stampResourceTypes(final Resource resource)
    {
        final String type = RESOURCE_TYPES.get(resource.getValueMap().get("jcr:primaryType", String.class));
        if (type != null && resource.getValueMap().get(TYPE) == null) {
            try {
                resource.adaptTo(ModifiableValueMap.class).put(TYPE, type);
                this.context.resourceResolver().commit();
            } catch (final PersistenceException e) {
                throw new IllegalStateException(e);
            }
        }
        resource.getChildren().forEach(this::stampResourceTypes);
    }

    private static byte[] bytes(final Resource file) throws IOException
    {
        try (InputStream content = file.adaptTo(InputStream.class)) {
            return content.readAllBytes();
        }
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
}
