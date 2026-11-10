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
package io.uhndata.iap.submissions.internal.transfer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.jcr.Binary;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonValue;

import org.apache.jackrabbit.api.JackrabbitSession;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.NodeTypeDefinitionScanner;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;
import org.apache.sling.testing.mock.sling.junit5.SlingContextExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.uhndata.iap.utils.PrefixTree;
import io.uhndata.iap.utils.copy.CopyParticipant;
import io.uhndata.iap.workflows.api.InvalidPayloadException;
import io.uhndata.iap.workflows.api.InvalidStateException;
import io.uhndata.iap.workflows.api.WorkflowFailedException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sends a submission through {@link SubmissionExporter} and {@link SubmissionImporter} on a real repository.
 *
 * <p>
 * One repository plays both instances. Between the export and the import, the submission is removed and what it
 * points at outside itself is recreated at the same path, so that it has a new identifier, as it would on another
 * instance, and so does everything the import makes.
 * </p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@ExtendWith(SlingContextExtension.class)
class SubmissionTransferTest
{
    private static final String HOME = "/Submissions";

    private static final String SCHEMA = "/Schemas/intake";

    private static final String QUESTION = SCHEMA + "/consent";

    private static final String ALICE_EMAIL = "alice@example.org";

    private static final String BOB_EMAIL = "bob@example.org";

    private static final byte[] SCAN = {0x25, 0x50, 0x44, 0x46, 0x00, (byte) 0xFF};

    private final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private final SubmissionExporter exporter = new SubmissionExporter();

    private final SubmissionImporter importer = new SubmissionImporter();

    private final String name = UUID.randomUUID().toString();

    private Session session;

    private UserManager users;

    @BeforeEach
    void setUp() throws Exception
    {
        this.session = this.context.resourceResolver().adaptTo(Session.class);
        NodeTypeDefinitionScanner.get().register(this.session, List.of("SLING-INF/nodetypes/transfer-test.cnd"),
            ResourceResolverType.JCR_OAK.getNodeTypeMode());
        this.users = ((JackrabbitSession) this.session).getUserManager();
        person("alice", ALICE_EMAIL);
        person("bob", BOB_EMAIL);
        this.users.createUser("carol", "password");
        this.users.createSystemUser("indexer", null);
        this.users.getAuthorizable("admin").setProperty("profile/email",
            this.session.getValueFactory().createValue("admin@example.org"));
        this.session.getRootNode().addNode("Schemas").addNode("intake").addMixin("mix:referenceable");
        question();
        this.session.getRootNode().addNode("Submissions");
        this.session.save();
        // Computed properties are left out of copies by the module that computes them
        participants(new CopyParticipant()
        {
            @Override
            public boolean skips(final Property property) throws RepositoryException
            {
                return "computed".equals(property.getName());
            }

            @Override
            public boolean skips(final Node child) throws RepositoryException
            {
                return "links".equals(child.getName());
            }
        });
    }

    @Test
    void aSubmissionArrivesWholeAndPointsAtWhatIsHere() throws Exception
    {
        final Node source = submission();
        final Map<String, String> before = identifiers(source);
        final String created = source.getProperty("jcr:created").getString();
        final SubmissionExport export = this.exporter.export(resource(source));
        final Map<String, byte[]> sent = read(export);
        moveToAnotherInstance(source);

        final Node received = node(importInto(export.manifest(), sent).getPath());
        this.session.save();

        assertEquals(PrefixTree.pathFor(HOME, this.name), received.getPath());
        assertTrue(received.isNodeType("test:Marker"));
        assertEquals("Consent", received.getProperty("title").getString());
        assertEquals(created, received.getProperty("originalCreated").getString());
        assertEquals("kept", received.getNode("meta").getProperty("note").getString());
        // What lies outside is found by its path: the schema kept its identifier, the question got a new one
        assertEquals(before.get(SCHEMA), received.getProperty("schema").getNode().getIdentifier());
        final Node answer = received.getNode("answers/a1");
        assertEquals(QUESTION, answer.getProperty("question").getNode().getPath());
        assertNotEquals(before.get(QUESTION), answer.getProperty("question").getNode().getIdentifier());
        // What lies inside points at the new copies
        final Node scan = received.getNode("documents/d1/v1");
        assertEquals(scan.getIdentifier(), answer.getProperty("sources").getValues()[0].getString());
        final Node comment = received.getNode("feedback/c1");
        assertEquals(List.of(scan.getPath(), QUESTION), paths(comment.getProperty("subjects")));
        assertEquals(received.getIdentifier(), comment.getProperty("about").getNode().getIdentifier());
        assertFalse(comment.hasProperty("gone"), "A weak reference to nothing is dropped");
        // People are found by their email, and keep it when they have no account here
        assertEquals("alice", received.getProperty("createdBy").getString());
        assertEquals(BOB_EMAIL, comment.getProperty("author").getString());
        assertEquals("ghost", comment.getProperty("reviewer").getString());
        assertEquals("carol", comment.getProperty("checkedBy").getString(), "Without an email, the id is all there is");
        assertEquals("admin", received.getNode("documents/d1").getProperty("createdBy").getString(),
            "Written outside the engine, so the repository saw the person");
        assertFalse(received.getNode("documents/indexed").hasProperty("createdBy"),
            "A service user is machinery, not a person");
        // The binary, its order among its siblings, and the rest of its file
        assertArrayEquals(SCAN, bytes(scan.getNode("file/jcr:content").getProperty("jcr:data").getBinary()));
        assertEquals("application/pdf", scan.getNode("file/jcr:content").getProperty("jcr:mimeType").getString());
        assertFalse(scan.getNode("file").hasProperty("originalCreated"), "nt:file takes no properties of its own");
        assertTrue(received.getNode("documents/d1/v2/file").hasProperty("originalCreated"),
            "unless a mixin lets it");
        assertEquals(List.of("v2", "v1"), names(received.getNode("documents/d1")));
        assertEquals(List.of(2L, 3L), longs(answer.getProperty("scores")));
        // What the repository, the engine and other modules maintain stays behind
        assertFalse(received.hasProperty("computed"));
        assertFalse(received.hasNode("wf:instances"));
        assertFalse(received.hasNode("links"));
        assertEquals("submission", received.getProperty("kind").getString());
    }

    @Test
    void aSubmissionPassedOnAgainKeepsTheDateItWasFirstCreated() throws Exception
    {
        final Node source = submission();
        source.setProperty("originalCreated", "2020-01-02T03:04:05.000Z", PropertyType.DATE);
        this.session.save();

        final JsonObject root = this.exporter.export(resource(source)).manifest().getJsonObject("root");

        assertTrue(root.getString("created").startsWith("2020-01-02T03:04:05"));
        assertFalse(root.getJsonObject("properties").containsKey("originalCreated"));
    }

    @Test
    void aSubmissionThatIsAlreadyHereIsRefused() throws Exception
    {
        final Node source = submission();
        final SubmissionExport export = this.exporter.export(resource(source));

        assertThrows(InvalidStateException.class, () -> importInto(export.manifest(), read(export)));
    }

    @Test
    void aSubmissionPointingAtWhatIsNotHereIsRefused() throws Exception
    {
        final Node source = submission();
        final SubmissionExport export = this.exporter.export(resource(source));
        final Map<String, byte[]> sent = read(export);
        moveToAnotherInstance(source);
        this.session.getNode(QUESTION).remove();
        this.session.save();

        final InvalidStateException refusal =
            assertThrows(InvalidStateException.class, () -> importInto(export.manifest(), sent));
        assertEquals("question points at " + QUESTION + ", which is not here", refusal.getMessage());
    }

    @Test
    void aManifestInAnotherFormatIsRefused()
    {
        assertThrows(InvalidPayloadException.class,
            () -> importInto(Json.createObjectBuilder().add("version", 2).build(), Map.of()));
    }

    @Test
    void aMalformedManifestIsRefused()
    {
        assertThrows(InvalidPayloadException.class,
            () -> importInto(Json.createObjectBuilder().add("version", 1).build(), Map.of()));
    }

    @Test
    void aNodeMissingWhatEveryNodeHasIsRefused() throws RepositoryException
    {
        final InvalidPayloadException noMixins = assertThrows(InvalidPayloadException.class, () -> importInto(
            manifest(this.name, Json.createObjectBuilder().add("name", "odd").add("type", "nt:unstructured")),
            Map.of()));
        assertEquals("The submission's manifest has no valid \"mixins\" where one belongs", noMixins.getMessage());
        // A refused run never commits what it had begun
        this.session.refresh(false);
        assertThrows(InvalidPayloadException.class,
            () -> importInto(manifest(this.name, Json.createObjectBuilder().add("name", "odd")), Map.of()));
    }

    @Test
    void aPropertyOfATypeJcrDoesNotHaveIsRefused()
    {
        final InvalidPayloadException refusal = assertThrows(InvalidPayloadException.class,
            () -> importInto(manifest(this.name, child("odd", "nt:unstructured").add("properties",
                Json.createObjectBuilder().add("p", Json.createObjectBuilder().add("type", "Nope").add("value", "x")))),
                Map.of()));
        assertEquals("The submission's manifest is malformed", refusal.getMessage());
    }

    @Test
    void aNameThatIsAPathIsRefused()
    {
        final InvalidPayloadException refusal = assertThrows(InvalidPayloadException.class,
            () -> importInto(manifest(this.name, child("../../escaped", "nt:unstructured")), Map.of()));
        assertEquals("\"../../escaped\" is not a node name", refusal.getMessage());
    }

    @Test
    void aNodeTypeThisInstanceDoesNotHaveIsRefused()
    {
        final InvalidPayloadException refusal = assertThrows(InvalidPayloadException.class,
            () -> importInto(manifest(this.name, child("odd", "test:Unknown")), Map.of()));
        assertTrue(refusal.getMessage().startsWith("The submission does not fit this instance"));
    }

    @Test
    void aBinaryThatWasNotSentIsRefused()
    {
        assertThrows(InvalidPayloadException.class, () -> importInto(manifest(this.name, scan("b0")), Map.of()));
    }

    @Test
    void aBinaryThatCannotBeReadFailsTheImport()
    {
        assertThrows(WorkflowFailedException.class, () -> this.importer.importInto(home(),
            manifest(this.name, scan("b0")), part -> {
                throw new IOException("The connection dropped");
            }));
    }

    @Test
    void aBinaryThatBreaksOffPartwayFailsTheImport()
    {
        // The repository reads the stream itself, so the failure reaches the import as the repository's own
        assertThrows(WorkflowFailedException.class, () -> this.importer.importInto(home(),
            manifest(this.name, scan("b0")), part -> new InputStream()
            {
                @Override
                public int read() throws IOException
                {
                    throw new IOException("The connection dropped");
                }
            }));
    }

    @Test
    void twoPeopleSharingAnEmailAreNeitherOfThem() throws Exception
    {
        person("alice-again", ALICE_EMAIL);

        assertNull(new UserDirectory(this.session).idOf(ALICE_EMAIL));
    }

    @Test
    void aRepositoryWithoutUsersCannotFindPeople()
    {
        assertThrows(RepositoryException.class, () -> new UserDirectory(Mockito.mock(Session.class)));
    }

    // ------------------------------------------------------------------------------------------------ fixtures

    /** Builds the submission at the frontdoor, with every kind of thing the transfer has to handle. */
    private Node submission() throws Exception
    {
        final Node bucket = PrefixTree.pendingBucketFor(home(), this.name, "sling:Folder").adaptTo(Node.class);
        final Node submission = bucket.addNode(this.name, "test:Submission");
        submission.addMixin("test:Marker");
        submission.setProperty("title", "Consent");
        submission.setProperty("schema", this.session.getNode(SCHEMA));
        submission.setProperty("createdBy", "alice");
        submission.setProperty("computed", "derived");
        submission.getNode("meta").setProperty("note", "kept");
        submission.addNode("wf:instances").addNode("i1");
        submission.addNode("links").addNode("l1");

        final Node documents = submission.addNode("documents");
        final Node document = documents.addNode("d1", "test:Part");
        version(document, "v1", SCAN);
        version(document, "v2", new byte[] {1});
        document.orderBefore("v2", "v1");
        // Written by a service user outside the engine; nt:unstructured lets this one be set by hand
        documents.addNode("indexed").setProperty("jcr:createdBy", "indexer");

        final Node answer = submission.addNode("answers").addNode("a1", "test:Part");
        answer.setProperty("question", this.session.getNode(QUESTION));
        answer.setProperty("sources", new Value[] {value(document.getNode("v1"), false)});
        answer.setProperty("scores", new String[] {"2", "3"}, PropertyType.LONG);
        answer.setProperty("createdBy", "alice");

        final Node gone = submission.addNode("doomed", "test:Part");
        final Node comment = submission.addNode("feedback").addNode("c1", "test:Part");
        comment.setProperty("author", "bob");
        comment.setProperty("reviewer", "ghost");
        comment.setProperty("checkedBy", "carol");
        comment.setProperty("subjects", new Value[] {value(document.getNode("v1"), true),
            value(this.session.getNode(QUESTION), true), value(gone, true)});
        comment.setProperty("about", value(submission, true));
        comment.setProperty("gone", value(gone, true));
        this.session.save();
        gone.remove();
        this.session.save();
        return submission;
    }

    private void version(final Node document, final String name, final byte[] content) throws Exception
    {
        final Node file = document.addNode(name, "test:Part").addNode("file", "nt:file");
        // A mixin that lets the file take a property of its own, and one that does not
        file.addMixin("v2".equals(name) ? "test:Annotated" : "mix:referenceable");
        final Node resource = file.addNode("jcr:content", "nt:resource");
        resource.setProperty("jcr:data",
            this.session.getValueFactory().createBinary(new ByteArrayInputStream(content)));
        resource.setProperty("jcr:mimeType", "application/pdf");
    }

    /** What the other instance looks like: the submission is not there, and its question has another identity. */
    private void moveToAnotherInstance(final Node source) throws Exception
    {
        source.remove();
        this.session.getNode(QUESTION).remove();
        question();
        this.users.getAuthorizable("bob").remove();
        this.session.save();
    }

    private void question() throws RepositoryException
    {
        this.session.getNode(SCHEMA).addNode("consent").addMixin("mix:referenceable");
    }

    private void person(final String id, final String email) throws RepositoryException
    {
        final User user = this.users.createUser(id, "password");
        user.setProperty("profile/email", this.session.getValueFactory().createValue(email));
        this.session.save();
    }

    private void participants(final CopyParticipant participant) throws Exception
    {
        final Field field = SubmissionExporter.class.getDeclaredField("participants");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        final List<CopyParticipant> list = (List<CopyParticipant>) field.get(this.exporter);
        list.add(participant);
    }

    private Resource importInto(final JsonObject manifest, final Map<String, byte[]> parts) throws Exception
    {
        return this.importer.importInto(home(), manifest, part -> parts.containsKey(part)
            ? new ByteArrayInputStream(parts.get(part)) : null);
    }

    private JsonObject manifest(final String root, final JsonObjectBuilder child)
    {
        return Json.createObjectBuilder().add("version", 1).add("root", Json.createObjectBuilder()
            .add("name", root).add("type", "nt:unstructured").add("mixins", JsonValue.EMPTY_JSON_ARRAY)
            .add("properties", JsonValue.EMPTY_JSON_OBJECT)
            .add("children", Json.createArrayBuilder().add(child))).build();
    }

    private static JsonObjectBuilder child(final String name, final String type)
    {
        return Json.createObjectBuilder().add("name", name).add("type", type)
            .add("mixins", JsonValue.EMPTY_JSON_ARRAY).add("properties", JsonValue.EMPTY_JSON_OBJECT)
            .add("children", JsonValue.EMPTY_JSON_ARRAY);
    }

    private static JsonObjectBuilder scan(final String part)
    {
        return child("scan", "nt:unstructured").add("properties", Json.createObjectBuilder()
            .add("data", Json.createObjectBuilder().add("type", "Binary").add("value", part)));
    }

    private Resource home()
    {
        return this.context.resourceResolver().getResource(HOME);
    }

    private Resource resource(final Node node) throws RepositoryException
    {
        return this.context.resourceResolver().getResource(node.getPath());
    }

    private Node node(final String path) throws RepositoryException
    {
        return this.session.getNode(path);
    }

    private Value value(final Node node, final boolean weak) throws RepositoryException
    {
        return this.session.getValueFactory().createValue(node, weak);
    }

    private Map<String, String> identifiers(final Node submission) throws RepositoryException
    {
        return Map.of(SCHEMA, this.session.getNode(SCHEMA).getIdentifier(),
            QUESTION, this.session.getNode(QUESTION).getIdentifier(),
            submission.getPath(), submission.getIdentifier());
    }

    /** Reads the binaries out before the submission they belong to is removed. */
    private static Map<String, byte[]> read(final SubmissionExport export) throws Exception
    {
        final Map<String, byte[]> parts = new HashMap<>();
        for (final Map.Entry<String, Binary> binary : export.binaries().entrySet()) {
            parts.put(binary.getKey(), bytes(binary.getValue()));
        }
        return parts;
    }

    private static byte[] bytes(final Binary binary) throws Exception
    {
        try (InputStream content = binary.getStream()) {
            return content.readAllBytes();
        }
    }

    private List<String> paths(final Property property) throws RepositoryException
    {
        final List<String> paths = new ArrayList<>();
        for (final Value value : property.getValues()) {
            paths.add(this.session.getNodeByIdentifier(value.getString()).getPath());
        }
        return paths;
    }

    private static List<Long> longs(final Property property) throws RepositoryException
    {
        final List<Long> longs = new ArrayList<>();
        for (final Value value : property.getValues()) {
            longs.add(value.getLong());
        }
        return longs;
    }

    private static List<String> names(final Node node) throws RepositoryException
    {
        final List<String> names = new ArrayList<>();
        final NodeIterator children = node.getNodes();
        while (children.hasNext()) {
            names.add(children.nextNode().getName());
        }
        return names;
    }
}
