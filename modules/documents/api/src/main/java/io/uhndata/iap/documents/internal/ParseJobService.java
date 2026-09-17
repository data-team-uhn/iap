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
package io.uhndata.iap.documents.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ModifiableValueMap;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.JobManager;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.uhndata.iap.documents.api.ParseService;

/**
 * Queues parses: a node under {@code /var/documents/jobs} recording the lifecycle, and a Sling job that calls the
 * daemon in the background. The parse endpoint is one caller; the platform's own workflows are another.
 *
 * <p>Where files are staged is the {@code sharedDocs} configuration property, then the {@code IAP_SHARED_DOCS}
 * environment variable, then {@code /shared-docs} - the same volume, at the same path, on both sides, which is
 * what lets the staged path be handed to the daemon as it is.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ParseService.class)
public class ParseJobService implements ParseService
{
    /** The configuration property naming the shared volume. */
    static final String SHARED_DOCS_PROPERTY = "sharedDocs";

    /** The environment variable naming the shared volume, same name on both sides. */
    static final String SHARED_DOCS_VARIABLE = "IAP_SHARED_DOCS";

    /** Where the shared volume is mounted when nothing says otherwise. */
    static final String DEFAULT_SHARED_DOCS = "/shared-docs";

    private static final Logger LOGGER = LoggerFactory.getLogger(ParseJobService.class);

    /** What a file is called when its name leaves nothing usable. */
    private static final String FALLBACK_NAME = "document";

    @Reference
    private ResourceResolverFactory resolverFactory;

    @Reference
    private JobManager jobManager;

    private Path sharedDocs;

    /**
     * Read where to stage files.
     *
     * @param configuration the component configuration
     */
    @Activate
    @Modified
    protected void activate(final Map<String, Object> configuration)
    {
        final Object configured = configuration.get(SHARED_DOCS_PROPERTY);
        String directory = configured == null ? "" : String.valueOf(configured).trim();
        if (directory.isEmpty()) {
            final String fromEnvironment = environment(SHARED_DOCS_VARIABLE);
            directory = fromEnvironment == null || fromEnvironment.isBlank() ? DEFAULT_SHARED_DOCS
                : fromEnvironment.trim();
        }
        this.sharedDocs = Path.of(directory);
    }

    @Override
    public String stage(final String fileName, final InputStream content) throws IOException
    {
        // A folder per file: two uploads may be called the same thing, and the daemon writes its outputs beside
        // the source
        final Path folder = Files.createDirectories(this.sharedDocs.resolve(UUID.randomUUID().toString()));
        final Path staged = folder.resolve(usableName(fileName));
        Files.copy(content, staged);
        return staged.toString();
    }

    @Override
    public String queue(final String path, final boolean chunk, final String target) throws IOException
    {
        final String jobId = UUID.randomUUID().toString();
        try (ResourceResolver resolver = ParseJob.openResolver(this.resolverFactory)) {
            final Resource jobsRoot = resolver.getResource(ParseJob.JOBS_PATH);
            if (jobsRoot == null) {
                throw new IOException("The parse jobs storage is not initialized");
            }
            final Resource jobNode = record(resolver, jobsRoot, jobId, path, chunk, target);
            final Job job = this.jobManager.addJob(ParseJob.TOPIC, Map.of(ParseJob.PN_JOB_ID, jobId));
            if (job == null) {
                markUnqueueable(resolver, jobNode);
                throw new IOException("The parse job could not be queued");
            }
            return jobId;
        } catch (final LoginException e) {
            LOGGER.error("Cannot access the parse jobs storage: {}", e.getMessage(), e);
            throw new IOException("The parse jobs storage is not accessible", e);
        }
    }

    /**
     * Where the file is staged.
     *
     * @return the shared volume's path
     */
    Path getSharedDocs()
    {
        return this.sharedDocs;
    }

    /**
     * Read an environment variable. Overridable so tests can stand in for the environment.
     *
     * @param name the variable
     * @return its value, or {@code null} when not set
     */
    protected String environment(final String name)
    {
        return System.getenv(name);
    }

    /**
     * A file name made safe for a file system: anything but letters, digits, dots, dashes and underscores becomes
     * an underscore, so nothing in it can name another directory, and a name left with nothing else gets a dull
     * one. The extension survives, which is what the daemon reads the document type from.
     */
    static String usableName(final String fileName)
    {
        final String usable = fileName == null ? "" : fileName.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        return usable.isEmpty() || usable.chars().allMatch(character -> character == '.' || character == '_')
            ? FALLBACK_NAME : usable;
    }

    private static Resource record(final ResourceResolver resolver, final Resource jobsRoot, final String jobId,
        final String path, final boolean chunk, final String target) throws IOException
    {
        final Map<String, Object> properties = new HashMap<>();
        properties.put(ParseJob.PN_JOB_ID, jobId);
        properties.put(ParseJob.PN_STATUS, ParseJob.STATUS_QUEUED);
        properties.put(ParseJob.PN_PATH, path);
        properties.put(ParseJob.PN_CHUNK, chunk);
        properties.put(ParseJob.PN_CREATED, Calendar.getInstance());
        if (target != null && !target.isBlank()) {
            properties.put(ParseJob.PN_TARGET, target);
        }
        try {
            final Resource jobNode = resolver.create(jobsRoot, jobId, properties);
            // The node must be visible to the consumer before the job is queued
            resolver.commit();
            return jobNode;
        } catch (final PersistenceException e) {
            LOGGER.error("Cannot record parse job {}: {}", jobId, e.getMessage(), e);
            throw new IOException("The parse job could not be recorded", e);
        }
    }

    private static void markUnqueueable(final ResourceResolver resolver, final Resource jobNode)
    {
        final ModifiableValueMap properties = jobNode.adaptTo(ModifiableValueMap.class);
        if (properties == null) {
            return;
        }
        properties.put(ParseJob.PN_STATUS, ParseJob.STATUS_FAILED);
        properties.put(ParseJob.PN_ERROR, "The job could not be queued");
        properties.put(ParseJob.PN_FINISHED, Calendar.getInstance());
        try {
            resolver.commit();
        } catch (final PersistenceException e) {
            LOGGER.error("Cannot mark parse job {} as failed: {}", jobNode.getName(), e.getMessage(), e);
        }
    }
}
