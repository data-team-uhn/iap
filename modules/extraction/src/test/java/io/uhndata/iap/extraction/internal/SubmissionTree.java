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
package io.uhndata.iap.extraction.internal;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.junit5.SlingContext;

/**
 * Builds the repository a handler test needs: a schema version with questions, a submission answering it, and
 * documents with uploads under it. JCR-backed, because the handlers write real references and binaries.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class SubmissionTree
{
    static final String VERSION_PATH = "/Schemas/proposal/v1";

    static final String FORM_PATH = VERSION_PATH + "/study";

    static final String SUBMISSION_PATH = "/Submissions/aa/bb/cc/proposal-1";

    private static final String TYPE = "sling:resourceType";

    private static final String SUPER_TYPE = "sling:resourceSuperType";

    private static final String PRIMARY_TYPE = "jcr:primaryType";

    private final SlingContext context;

    private int documents;

    SubmissionTree(final SlingContext context)
    {
        this.context = context;
    }

    /** The schema version, with one form to hold questions. */
    Resource schemaVersion()
    {
        this.context.create().resource("/Schemas/proposal", Map.of(TYPE, "sch/Schema", "title", "Proposal"));
        final Resource version = this.context.create().resource(VERSION_PATH,
            Map.of(TYPE, "sch/SchemaVersion", "version", "1.0", "active", true));
        // A mock repository has no /libs/sch hierarchy to inherit from, so each item carries its super type
        this.context.create().resource(FORM_PATH,
            Map.of(TYPE, "sch/FormRequirement", SUPER_TYPE, "sch/Requirement", "label", "Study"));
        return version;
    }

    /** A question in the form; with a prompt, one the intake reads out of the document. */
    Resource question(final String name, final String prompt, final String... tags)
    {
        final Map<String, Object> properties = new HashMap<>();
        properties.put(TYPE, "sch/Question");
        properties.put(SUPER_TYPE, "sch/FormItem");
        properties.put("text", "What is the " + name + "?");
        properties.put("dataType", "text");
        if (prompt != null) {
            properties.put("extractionPrompt", prompt);
        }
        if (tags.length > 0) {
            properties.put("rubricTags", tags);
        }
        return this.context.create().resource(FORM_PATH + "/" + name, properties);
    }

    /** The submission, answering the schema version, still a draft. */
    Resource submission()
    {
        final Resource submission = this.context.create().resource(SUBMISSION_PATH, Map.of(
            TYPE, "sub/Submission", "title", "A proposal", "createdBy", "demo-researcher",
            "tags", new String[] { "draft" }));
        reference(submission, "schemaVersion", this.context.resourceResolver().getResource(VERSION_PATH));
        return submission;
    }

    /**
     * A document with one version holding an upload.
     *
     * @param parseStatus where its parse got to, or {@code null} for one never sent
     * @return the {@code sub:File}
     */
    Resource file(final String parseStatus)
    {
        this.documents++;
        final String document = SUBMISSION_PATH + "/d" + this.documents;
        this.context.create().resource(document, Map.of(TYPE, "sub/Document", "title", "proposal.pdf"));
        this.context.create().resource(document + "/v1", Map.of(TYPE, "sub/DocumentVersion"));
        final Map<String, Object> properties = new HashMap<>();
        properties.put(TYPE, "sub/File");
        properties.put("fileName", "proposal.pdf");
        if (parseStatus != null) {
            properties.put("parseStatus", parseStatus);
        }
        final Resource file = this.context.create().resource(document + "/v1/file", properties);
        storeText(file.getPath() + "/uploadedFile", "%PDF-1.4 the upload");
        return file;
    }

    /** A document whose version holds no upload yet. */
    Resource emptyDocument()
    {
        this.documents++;
        final String document = SUBMISSION_PATH + "/d" + this.documents;
        this.context.create().resource(document, Map.of(TYPE, "sub/Document", "title", "pending"));
        return this.context.create().resource(document + "/v1", Map.of(TYPE, "sub/DocumentVersion"));
    }

    /** One chunk of a parsed file, with its text. */
    Resource chunk(final Resource file, final String name, final String text)
    {
        final String chunks = file.getPath() + "/chunks";
        if (this.context.resourceResolver().getResource(chunks) == null) {
            this.context.create().resource(chunks, Map.of(TYPE, "sub/Chunks"));
        }
        final Resource chunk = this.context.create().resource(chunks + "/" + name, Map.of(TYPE, "sub/Chunk"));
        storeText(chunk.getPath() + "/content", text);
        return chunk;
    }

    /** A JCR REFERENCE, which the resolver cannot write. */
    static void reference(final Resource from, final String property, final Resource to)
    {
        try {
            from.adaptTo(Node.class).setProperty(property, to.adaptTo(Node.class));
            from.getResourceResolver().commit();
        } catch (final RepositoryException | PersistenceException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The identifier a REFERENCE to a node holds. */
    static String identifierOf(final Resource resource)
    {
        try {
            return resource.adaptTo(Node.class).getIdentifier();
        } catch (final RepositoryException e) {
            throw new IllegalStateException(e);
        }
    }

    private void storeText(final String path, final String text)
    {
        final Resource file = this.context.create().resource(path, Map.of(PRIMARY_TYPE, "nt:file"));
        this.context.create().resource(file.getPath() + "/jcr:content", Map.of(
            PRIMARY_TYPE, "nt:resource",
            "jcr:data", new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))));
    }
}
