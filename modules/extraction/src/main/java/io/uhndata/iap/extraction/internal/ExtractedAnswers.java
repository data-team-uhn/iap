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

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;

import io.uhndata.iap.schemas.models.Question;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.utils.ReferenceUtils;

/**
 * Writes one run of the model against a question: a {@code sub:Extraction} under the question's
 * {@code sub:Answer}, saying what it read and where from, and a {@code sub:Context} per quote. The answer's value
 * is the submitter's alone, so nothing here writes it.
 *
 * <p>Kept apart from the intake so that every step recording extracted answers writes the same nodes.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class ExtractedAnswers
{
    private static final String PRIMARY_TYPE = "jcr:primaryType";

    private static final String QUESTION_PROPERTY = "question";

    private static final String SOURCES_PROPERTY = "sources";

    private ExtractedAnswers()
    {
        // Utility
    }

    /**
     * Record one run of the model against a question, under its answer, creating an empty answer when there is none.
     * The answer's value is never written: a suggestion is not an answer until the submitter accepts or edits it.
     *
     * @param resolver the session to write through
     * @param target the submission
     * @param existing the answer to the same question to write under, or {@code null} to create one
     * @param question the question that was asked
     * @param result what the model found, or that it found nothing
     * @param files the files it was read from, all of them when several were sent as one text
     * @throws PersistenceException if anything cannot be written
     */
    static void write(final ResourceResolver resolver, final Resource target, final Resource existing,
        final Question question, final FieldResult result, final List<File> files) throws PersistenceException
    {
        // Everything the run points at is looked up before anything is written, so a missing one leaves no
        // half-made answer behind for the engine's commit to refuse
        final Resource questionResource = resolver.getResource(question.getPath());
        if (questionResource == null) {
            throw new PersistenceException("Could not read the question " + question.getPath());
        }
        final List<Resource> versions = getVersions(resolver, files);
        if (existing != null) {
            writeExtraction(resolver, existing, result, versions);
            return;
        }
        final Resource answer = resolver.create(target, UUID.randomUUID().toString(),
            Map.of(PRIMARY_TYPE, "sub:Answer"));
        try {
            ReferenceUtils.setReference(answer, QUESTION_PROPERTY, questionResource);
            writeExtraction(resolver, answer, result, versions);
        } catch (final PersistenceException e) {
            resolver.delete(answer);
            throw e;
        }
    }

    /** The versions the files belong to, which are what a run read. */
    private static List<Resource> getVersions(final ResourceResolver resolver, final List<File> files)
        throws PersistenceException
    {
        final List<Resource> versions = new ArrayList<>();
        for (final File file : files) {
            final Resource fileResource = resolver.getResource(file.getPath());
            if (fileResource == null || fileResource.getParent() == null) {
                throw new PersistenceException("Could not read the document " + file.getPath());
            }
            versions.add(fileResource.getParent());
        }
        return versions;
    }

    /**
     * Write the run under an answer, with its quotes, removing it again if any of it cannot be written. A run that
     * found nothing has no extracted answer, and still names the versions it read.
     */
    private static void writeExtraction(final ResourceResolver resolver, final Resource answer,
        final FieldResult result, final List<Resource> versions) throws PersistenceException
    {
        final Map<String, Object> extraction = new HashMap<>();
        extraction.put(PRIMARY_TYPE, "sub:Extraction");
        if (result.found()) {
            extraction.put("extractedAnswer", result.value());
        }
        extraction.put("confidence", result.confidence());
        if (result.reasoning() != null) {
            extraction.put("reasoning", result.reasoning());
        }
        final Resource extracted = resolver.create(answer, UUID.randomUUID().toString(), extraction);
        try {
            ReferenceUtils.setReferences(extracted, SOURCES_PROPERTY, versions);
            for (final FieldResult.Passage passage : result.passages()) {
                writeContext(resolver, extracted, passage,
                    passage.part() >= 0 && passage.part() < versions.size() ? versions.get(passage.part()) : null);
            }
        } catch (final PersistenceException e) {
            resolver.delete(extracted);
            throw e;
        }
    }

    private static void writeContext(final ResourceResolver resolver, final Resource extracted,
        final FieldResult.Passage passage, final Resource source) throws PersistenceException
    {
        final Map<String, Object> context = new HashMap<>();
        context.put(PRIMARY_TYPE, "sub:Context");
        context.put("quote", passage.quote());
        // Every quote stored here was found in the text. The ones that were not are dropped upstream.
        if (passage.header() != null && !passage.header().isEmpty()) {
            context.put("header", passage.header());
        }
        if (passage.page() != null) {
            context.put("page", passage.page());
        }
        final Resource written = resolver.create(extracted, UUID.randomUUID().toString(), context);
        // Only when the step named its documents: then each quote points at the one it came from
        if (source != null) {
            ReferenceUtils.setWeakReference(written, "source", source);
        }
    }
}
