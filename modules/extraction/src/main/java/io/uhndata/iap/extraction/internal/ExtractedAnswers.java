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

import org.apache.sling.api.resource.ModifiableValueMap;
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

    private static final String VALUE = "value";

    private ExtractedAnswers()
    {
        // Utility
    }

    /**
     * Record one answer, its extraction and its evidence.
     *
     * @param resolver the session to write through
     * @param target the submission
     * @param existing an empty answer to the same question to fill, or {@code null} to create one
     * @param question the question being answered
     * @param result what the model found
     * @param files the files it was read from, all of them when several were sent as one text
     * @throws PersistenceException if anything cannot be written
     */
    static void write(final ResourceResolver resolver, final Resource target, final Resource existing,
        final Question question, final FieldResult result, final List<File> files) throws PersistenceException
    {
        // Everything the answer points at is looked up before anything is written, so a missing one leaves no
        // half-made answer behind for the engine's commit to refuse or a later reading to skip
        final Resource questionResource = resolver.getResource(question.getPath());
        if (questionResource == null) {
            throw new PersistenceException("Could not read the question " + question.getPath());
        }
        // The versions the files belong to are what the answer was read from
        final List<Resource> versions = new ArrayList<>();
        for (final File file : files) {
            final Resource fileResource = resolver.getResource(file.getPath());
            if (fileResource == null || fileResource.getParent() == null) {
                throw new PersistenceException("Could not read the document " + file.getPath());
            }
            versions.add(fileResource.getParent());
        }
        final String[] values = readValues(question, result.value());
        if (existing != null) {
            fill(resolver, existing, values, result, versions);
            return;
        }
        final Resource answer = resolver.create(target, UUID.randomUUID().toString(),
            Map.of(PRIMARY_TYPE, "sub:Answer", VALUE, values));
        try {
            reference(answer, QUESTION, questionResource);
            writeExtraction(resolver, answer, result, versions);
        } catch (final PersistenceException e) {
            resolver.delete(answer);
            throw e;
        }
    }

    /**
     * Put the answer into an empty one the form already saved for the question, putting it back as it was if
     * the extraction under it cannot be written.
     */
    private static void fill(final ResourceResolver resolver, final Resource existing, final String[] values,
        final FieldResult result, final List<Resource> versions) throws PersistenceException
    {
        final ModifiableValueMap properties = existing.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            throw new PersistenceException("Not allowed to fill the answer " + existing.getPath());
        }
        final Object before = properties.get(VALUE);
        properties.put(VALUE, values);
        try {
            writeExtraction(resolver, existing, result, versions);
        } catch (final PersistenceException e) {
            if (before == null) {
                properties.remove(VALUE);
            } else {
                properties.put(VALUE, before);
            }
            throw e;
        }
    }

    /**
     * Write the extraction under an answer, with its evidence, removing it again if any of it cannot be written.
     */
    private static void writeExtraction(final ResourceResolver resolver, final Resource answer,
        final FieldResult result, final List<Resource> versions) throws PersistenceException
    {
        final Map<String, Object> extraction = new HashMap<>();
        extraction.put(PRIMARY_TYPE, "sub:Extraction");
        extraction.put("extractedAnswer", result.value());
        extraction.put("confidence", result.confidence());
        if (result.reasoning() != null) {
            extraction.put("reasoning", result.reasoning());
        }
        final Resource extracted = resolver.create(answer, UUID.randomUUID().toString(), extraction);
        try {
            referenceSources(extracted, versions);
            for (final FieldResult.Passage passage : result.passages()) {
                writeEvidence(resolver, extracted, passage,
                    passage.part() >= 0 && passage.part() < versions.size() ? versions.get(passage.part()) : null);
            }
        } catch (final PersistenceException e) {
            resolver.delete(extracted);
            throw e;
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
            throw new PersistenceException(describeMissingReference(from, property));
        }
        try {
            node.setProperty(property, referenced);
        } catch (final RepositoryException e) {
            throw new PersistenceException(describeMissingReference(from, property), e);
        }
    }

    private static String describeMissingReference(final Resource from, final String property)
    {
        return "Could not reference " + property + " from " + from.getPath();
    }

    /** The multi-valued REFERENCE to every version an extraction read. */
    private static void referenceSources(final Resource from, final List<Resource> versions)
        throws PersistenceException
    {
        final Node node = from.adaptTo(Node.class);
        final List<Node> referenced = new ArrayList<>();
        versions.forEach(version -> referenced.add(version.adaptTo(Node.class)));
        if (node == null || referenced.contains(null)) {
            throw new PersistenceException(describeMissingReference(from, SOURCES));
        }
        try {
            final Value[] values = new Value[referenced.size()];
            for (int i = 0; i < values.length; i++) {
                values[i] = node.getSession().getValueFactory().createValue(referenced.get(i));
            }
            node.setProperty(SOURCES, values);
        } catch (final RepositoryException e) {
            throw new PersistenceException(describeMissingReference(from, SOURCES), e);
        }
    }
}
