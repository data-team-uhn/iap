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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Value;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.models.File;

/**
 * Writes one answer the model read: the {@code sub:Answer}, the {@code sub:Extraction} under it saying where
 * it came from, and a {@code sub:Evidence} per quote.
 *
 * <p>Shared by the first pass and Step 2. Both produce the same thing, and two copies of this would drift.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ExtractedAnswers
{
    private static final String PRIMARY_TYPE = "jcr:primaryType";

    private static final String QUESTION = "question";

    private static final String SOURCES = "sources";

    private ExtractedAnswers()
    {
        // Utility
    }

    /**
     * Record one answer, its extraction and its evidence.
     *
     * @param resolver the session to write through
     * @param target the submission
     * @param question the question being answered
     * @param result what the model found
     * @param file the file it was read from
     * @throws PersistenceException if anything cannot be written
     */
    static void write(final ResourceResolver resolver, final Resource target, final Question question,
        final FieldResult result, final File file) throws PersistenceException
    {
        final Resource answer = resolver.create(target, UUID.randomUUID().toString(),
            Map.of(PRIMARY_TYPE, "sub:Answer", "value", new String[] { result.value() }));
        reference(answer, QUESTION, resolver.getResource(question.getPath()));
        final Map<String, Object> extraction = new HashMap<>();
        extraction.put(PRIMARY_TYPE, "sub:Extraction");
        extraction.put("extractedAnswer", result.value());
        extraction.put("confidence", result.confidence());
        extraction.put("needsSecondLook", result.needsSecondLook());
        if (result.reasoning() != null) {
            extraction.put("reasoning", result.reasoning());
        }
        final Resource extracted = resolver.create(answer, UUID.randomUUID().toString(), extraction);
        // The version the file belongs to is what the answer was read from
        final Resource fileResource = resolver.getResource(file.getPath());
        reference(extracted, SOURCES, fileResource == null ? null : fileResource.getParent());
        for (final FieldResult.Passage passage : result.passages()) {
            writeEvidence(resolver, extracted, passage, file);
        }
    }

    private static void writeEvidence(final ResourceResolver resolver, final Resource extracted,
        final FieldResult.Passage passage, final File file) throws PersistenceException
    {
        final Map<String, Object> evidence = new HashMap<>();
        evidence.put(PRIMARY_TYPE, "sub:Evidence");
        evidence.put("quote", passage.quote());
        // Every quote stored here was found in the text. The ones that were not are dropped upstream.
        if (passage.header() != null && !passage.header().isEmpty()) {
            evidence.put("header", passage.header());
        }
        if (passage.page() != null) {
            evidence.put("page", passage.page());
        }
        final Resource written = resolver.create(extracted, UUID.randomUUID().toString(), evidence);
        final Resource chunk = passage.chunkId() == null ? null : resolver.getResource(
            file.getPath() + "/" + ParsePropertyNames.CHUNKS_CHILD + "/" + passage.chunkId());
        if (chunk != null) {
            reference(written, "chunk", chunk);
        }
    }

    /**
     * Write a REFERENCE. The resolver cannot: it would store a string and the strict node types would refuse
     * the commit.
     */
    private static void reference(final Resource from, final String property, final Resource to)
        throws PersistenceException
    {
        final Node node = from.adaptTo(Node.class);
        final Node referenced = to == null ? null : to.adaptTo(Node.class);
        if (node == null || referenced == null) {
            throw new PersistenceException("Could not reference " + property + " from " + from.getPath());
        }
        try {
            if (SOURCES.equals(property)) {
                node.setProperty(property,
                    new Value[] { node.getSession().getValueFactory().createValue(referenced) });
            } else {
                node.setProperty(property, referenced);
            }
        } catch (final RepositoryException e) {
            throw new PersistenceException("Could not reference " + property + " from " + from.getPath(), e);
        }
    }
}
