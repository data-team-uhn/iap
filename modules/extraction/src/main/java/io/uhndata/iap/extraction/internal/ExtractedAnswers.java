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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Value;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.models.Answer;
import io.uhndata.iap.submissions.models.File;

/**
 * Writes one answer the model read: the {@code sub:Answer}, the {@code sub:Extraction} under it saying where
 * it came from, and a {@code sub:Evidence} per quote.
 *
 * <p>Kept apart from the intake so that every step recording extracted answers writes the same nodes.
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
     * @param files the files it was read from, all of them when several were sent as one text
     * @throws PersistenceException if anything cannot be written
     */
    static void write(final ResourceResolver resolver, final Resource target, final Question question,
        final FieldResult result, final List<File> files) throws PersistenceException
    {
        final Resource answer = resolver.create(target, UUID.randomUUID().toString(),
            Map.of(PRIMARY_TYPE, "sub:Answer", "value", readValues(question, result.value())));
        reference(answer, QUESTION, resolver.getResource(question.getPath()));
        final Map<String, Object> extraction = new HashMap<>();
        extraction.put(PRIMARY_TYPE, "sub:Extraction");
        extraction.put("extractedAnswer", result.value());
        extraction.put("confidence", result.confidence());
        if (result.reasoning() != null) {
            extraction.put("reasoning", result.reasoning());
        }
        final Resource extracted = resolver.create(answer, UUID.randomUUID().toString(), extraction);
        // The versions the files belong to are what the answer was read from
        final List<Resource> versions = new ArrayList<>();
        for (final File file : files) {
            final Resource fileResource = resolver.getResource(file.getPath());
            versions.add(fileResource == null ? null : fileResource.getParent());
        }
        referenceSources(extracted, versions);
        for (final FieldResult.Passage passage : result.passages()) {
            writeEvidence(resolver, extracted, passage,
                passage.part() >= 0 && passage.part() < versions.size() ? versions.get(passage.part()) : null);
        }
    }

    /**
     * The answer as the values to store, split by the same rule the form shows the suggestion under. Shared
     * rather than repeated: the two lists are compared against each other to tell an untouched suggestion from a
     * corrected one, so a difference between them is a difference nobody made.
     */
    private static String[] readValues(final Question question, final String value)
    {
        return Answer.readAnswer(value, question).toArray(new String[0]);
    }

    private static void writeEvidence(final ResourceResolver resolver, final Resource extracted,
        final FieldResult.Passage passage, final Resource source) throws PersistenceException
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
        // Only when several documents were read as one text: then a quote came from one of them in particular
        if (source != null) {
            reference(written, "source", source);
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
            throw new PersistenceException(cannotReference(from, property));
        }
        try {
            node.setProperty(property, referenced);
        } catch (final RepositoryException e) {
            throw new PersistenceException(cannotReference(from, property), e);
        }
    }

    private static String cannotReference(final Resource from, final String property)
    {
        return "Could not reference " + property + " from " + from.getPath();
    }

    /** The multi-valued REFERENCE to every version an extraction read. */
    private static void referenceSources(final Resource from, final List<Resource> versions)
        throws PersistenceException
    {
        final Node node = from.adaptTo(Node.class);
        final List<Node> referenced = new ArrayList<>();
        versions.forEach(version -> referenced.add(version == null ? null : version.adaptTo(Node.class)));
        if (node == null || referenced.contains(null)) {
            throw new PersistenceException(cannotReference(from, SOURCES));
        }
        try {
            final Value[] values = new Value[referenced.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = node.getSession().getValueFactory().createValue(referenced.get(i));
            }
            node.setProperty(SOURCES, values);
        } catch (final RepositoryException e) {
            throw new PersistenceException(cannotReference(from, SOURCES), e);
        }
    }
}
