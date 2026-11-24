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
package io.uhndata.iap.workflows.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.apache.sling.testing.mock.sling.context.SlingContextImpl;
import org.jetbrains.annotations.NotNull;

/**
 * Installs the workflow resource types this module ships under {@code /libs/wf} in a test's context. A node's
 * type autocreates only one supertype. Each step further up, from {@code wf/StartEvent} to {@code wf/FlowNode},
 * comes from the type's own node under {@code /libs}, which names its parent. Sling Models picks a flow node's model
 * by walking that chain, and the engine finds a workflow's flow nodes by it.
 *
 * <p>The types are read from the bundle's own initial content, so a type added there is installed here too.</p>
 *
 * @version $Id$
 * @since 0.1.0
 */
public final class WorkflowResourceTypes
{
    /** Where the bundle keeps the types, one folder each, holding a {@code ROOT.json}. */
    private static final String SHIPPED = "SLING-INF/content/libs/wf";

    private static final String INSTALLED = "/libs/wf/";

    private WorkflowResourceTypes()
    {
    }

    /**
     * Installs each type the context does not have yet. Installing twice changes nothing, so a test may install the
     * types and also use {@link WorkflowPlugins#ENGINE}, which installs them too.
     *
     * @param context the context to install the types in
     */
    public static void installIn(@NotNull final SlingContextImpl context)
    {
        shippedTypes().stream()
            .filter(type -> context.resourceResolver().getResource(INSTALLED + type) == null)
            .forEach(type -> context.load().json("/" + SHIPPED + "/" + type + "/ROOT.json", INSTALLED + type));
    }

    private static List<String> shippedTypes()
    {
        try {
            final URI shipped = Objects.requireNonNull(
                WorkflowResourceTypes.class.getClassLoader().getResource(SHIPPED),
                "The workflows bundle is on the test classpath").toURI();
            if ("jar".equals(shipped.getScheme())) {
                try (FileSystem bundle = FileSystems.newFileSystem(shipped, Map.of())) {
                    return namesIn(bundle.getPath(SHIPPED));
                }
            }
            return namesIn(Path.of(shipped));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } catch (final URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> namesIn(final Path folder) throws IOException
    {
        try (Stream<Path> types = Files.list(folder)) {
            return types.map(type -> type.getFileName().toString().replace("/", "")).toList();
        }
    }
}
