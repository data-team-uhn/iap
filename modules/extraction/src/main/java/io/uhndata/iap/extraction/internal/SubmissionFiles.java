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

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.errortracking.api.ErrorContext;
import io.uhndata.iap.errortracking.api.ErrorLogger;
import io.uhndata.iap.schemas.models.DocumentRequirement;
import io.uhndata.iap.schemas.models.Requirement;
import io.uhndata.iap.submissions.models.Document;
import io.uhndata.iap.submissions.models.DocumentVersion;
import io.uhndata.iap.submissions.models.File;
import io.uhndata.iap.submissions.models.Submission;
import io.uhndata.iap.workflows.models.WorkflowVersion;

/**
 * Helpers that find a submission and its files for the reading steps. A document's file is always its latest
 * upload, and documents come in the order the repository lists them.
 *
 * @version $Id$
 * @since 0.1.0
 */
final class SubmissionFiles
{
    /** Where a schema version names the workflow that reads its documents. */
    static final String READING_WORKFLOW = "readingWorkflow";

    /** What the submitter is told when no document a reading can read has been uploaded. */
    static final String NO_DOCUMENT_MESSAGE = "No document to read has been uploaded";

    private static final Logger LOGGER = LoggerFactory.getLogger(SubmissionFiles.class);

    private SubmissionFiles()
    {
        // Utility
    }

    /**
     * Whether a schema version names a workflow that reads its documents, and whether that workflow can run.
     *
     * @version $Id$
     * @since 0.1.0
     */
    enum ReadingWorkflow
    {
        /** The schema names none: it never reads its documents. */
        NONE,
        /** The schema names one that is missing, is not a workflow version, or is not tagged active. */
        CANNOT_RUN,
        /** The schema names a workflow version that exists and is tagged active. */
        CAN_RUN
    }

    /**
     * Checks the reading workflow the submission's schema version names. Parsing and the reading job both ask this,
     * so they agree on whether anything will read the documents.
     *
     * @param submission the submission
     * @param resolver a session that can read the workflow version
     * @return whether there is one, and whether it can run
     */
    static ReadingWorkflow checkReadingWorkflow(final Submission submission, final ResourceResolver resolver)
    {
        final String identifier = submission.getSchemaVersion().get(READING_WORKFLOW, String.class);
        if (identifier == null) {
            return ReadingWorkflow.NONE;
        }
        final Session session = Objects.requireNonNull(resolver.adaptTo(Session.class),
            "A repository session is always JCR-backed");
        try {
            final Resource reading = resolver.getResource(session.getNodeByIdentifier(identifier).getPath());
            final WorkflowVersion version = reading == null || !reading.isResourceType(WorkflowVersion.RESOURCE_TYPE)
                ? null : reading.adaptTo(WorkflowVersion.class);
            return version != null && version.isActive() ? ReadingWorkflow.CAN_RUN : ReadingWorkflow.CANNOT_RUN;
        } catch (final RepositoryException | IllegalArgumentException e) {
            // The repository refuses an identifier that is not a UUID with an IllegalArgumentException
            return ReadingWorkflow.CANNOT_RUN;
        }
    }

    /**
     * Tells an administrator that the schema of a submission names a reading workflow that cannot run.
     *
     * @param reporter the class that found it
     * @param submission the submission's resource
     */
    static void reportReadingWorkflowCannotRun(final Class<?> reporter, final Resource submission)
    {
        LOGGER.warn("The schema of {} names a reading workflow that cannot run", submission.getPath());
        ErrorLogger.logProblem("The schema version names a reading workflow that does not exist or is not active",
            ErrorContext.of(reporter, "findReadingWorkflow").about(submission));
    }

    /**
     * The submission a node belongs to: the node itself when it is a submission, or the closest submission above.
     *
     * @param part a submission, or any node under one
     * @return the submission's resource, or {@code null} when the node is not under one
     */
    static Resource findSubmission(final Resource part)
    {
        Resource current = part;
        while (current != null && !current.isResourceType(Submission.RESOURCE_TYPE)) {
            current = current.getParent();
        }
        return current;
    }

    /**
     * The submission resource as a model. Throws {@code NullPointerException} when the resource is not a
     * submission.
     *
     * @param target the submission's resource
     * @return the model, never {@code null}
     */
    static Submission getSubmission(final Resource target)
    {
        return Objects.requireNonNull(target.adaptTo(Submission.class),
            "The extraction workflows only apply to submissions");
    }

    /**
     * The latest upload of every document, in the order the repository lists the documents.
     *
     * @param submission the submission
     * @return the current files; documents with no upload yet are skipped
     */
    static List<File> getCurrentFiles(final Submission submission)
    {
        return getCurrentFiles(submission.getDocuments().stream());
    }

    /**
     * The latest upload of every document whose requirement does not skip parsing, in the order the repository
     * lists the documents. These are the only files ever parsed, so they are the only ones a reading can read.
     *
     * @param submission the submission
     * @return the current files to parse; documents with no upload yet are skipped
     */
    static List<File> getFilesToParse(final Submission submission)
    {
        return getCurrentFiles(submission.getDocuments().stream().filter(document -> !isParsingSkipped(document)));
    }

    private static List<File> getCurrentFiles(final Stream<Document> documents)
    {
        return documents.map(SubmissionFiles::getCurrentFile).filter(Objects::nonNull).toList();
    }

    private static File getCurrentFile(final Document document)
    {
        final DocumentVersion version = document.getCurrentVersion();
        return version == null ? null : version.getFile();
    }

    private static boolean isParsingSkipped(final Document document)
    {
        return document.getFulfills() instanceof DocumentRequirement requirement && requirement.isParsingSkipped();
    }

    /**
     * Why there is nothing to read, checked in this order: no upload, a failed parse, a parse still queued, or
     * else no document could be read. A failed parse gets a fixed message; its real reason stays on the file and
     * in the log.
     *
     * @param submission the submission
     * @return the reason, ready to show to the submitter
     */
    static String describeNothingToRead(final Submission submission)
    {
        final List<File> files = getFilesToParse(submission);
        if (files.isEmpty()) {
            return NO_DOCUMENT_MESSAGE;
        }
        for (final File file : files) {
            if (ParsePropertyNames.STATUS_FAILED.equals(file.getParseStatus())) {
                return describeUnreadable();
            }
        }
        for (final File file : files) {
            if (ParsePropertyNames.STATUS_QUEUED.equals(file.getParseStatus())) {
                return "The document has not been read yet";
            }
        }
        return "No uploaded document could be read";
    }

    /**
     * The first file whose parse finished, in the order the repository lists the documents. The intake reads it
     * when its step names no documents.
     *
     * @param submission the submission
     * @return the file, or {@code null} when no parse has finished
     */
    static File findFirstParsed(final Submission submission)
    {
        for (final File file : getFilesToParse(submission)) {
            if (ParsePropertyNames.STATUS_COMPLETED.equals(file.getParseStatus())) {
                return file;
            }
        }
        return null;
    }

    /**
     * The message the submitter sees when a file could not be parsed. The real reason stays on the file and in
     * the log: it is about the parsing service, and the submitter cannot act on it.
     *
     * @return the message, ready to show
     */
    static String describeUnreadable()
    {
        return "The document could not be read. Please try again";
    }

    /**
     * The latest upload for one document requirement, whatever its parse status.
     *
     * @param submission the submission
     * @param requirement the name of the document requirement in the schema version
     * @return the latest upload, or {@code null} when no document for that requirement has an upload
     */
    static File getCurrentFile(final Submission submission, final String requirement)
    {
        for (final Document document : submission.getDocuments()) {
            final Requirement fulfilled = document.getFulfills();
            final File file = getCurrentFile(document);
            if (fulfilled != null && requirement.equals(fulfilled.getName()) && file != null) {
                return file;
            }
        }
        return null;
    }

    /**
     * Whether no parse is still waiting, so what can be read now is all there will be.
     *
     * @param submission the submission
     * @return {@code true} when no current file's parse is queued, including files never sent to be parsed
     */
    static boolean isEveryParseSettled(final Submission submission)
    {
        for (final File file : getCurrentFiles(submission)) {
            // A file never sent to be parsed has no status, and is not waiting for anything
            if (ParsePropertyNames.STATUS_QUEUED.equals(file.getParseStatus())) {
                return false;
            }
        }
        return true;
    }
}
