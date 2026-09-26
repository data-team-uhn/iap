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
package io.uhndata.iap.schemas.editing.internal;

import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.Property;
import javax.jcr.PropertyIterator;
import javax.jcr.RepositoryException;

import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.Resource;
import org.osgi.service.component.annotations.Component;

import io.uhndata.iap.schemas.models.Schema;
import io.uhndata.iap.schemas.models.SchemaVersion;
import io.uhndata.iap.workflows.api.NoApplicableWorkflowException;
import io.uhndata.iap.workflows.api.WorkflowException;
import io.uhndata.iap.workflows.spi.ServiceTaskHandler;
import io.uhndata.iap.workflows.spi.WorkflowTaskContext;

/**
 * Deletes a draft version, or a schema none of whose versions was ever published: content nothing can depend on
 * yet. Anything published stays, retired rather than deleted, because submissions may reference it; and even a
 * draft stays while something outside it refers to it, e.g. a category bound to it.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Component(service = ServiceTaskHandler.class)
public class DiscardSchemaContentHandler implements ServiceTaskHandler
{
    /** The name activities use to point at this handler. */
    public static final String HANDLER_NAME = "discardSchemaContent";

    @Override
    public String getName()
    {
        return HANDLER_NAME;
    }

    @Override
    public void execute(final WorkflowTaskContext context) throws WorkflowException, PersistenceException
    {
        final Resource target = context.getTarget();
        final SchemaVersion version = SchemaContent.asVersion(target);
        final Schema schema = SchemaContent.asSchema(target);
        if (version != null) {
            if (!version.isDraft()) {
                throw new NoApplicableWorkflowException("Version " + version.getVersion()
                    + " has been published; retire it instead");
            }
        } else if (schema != null) {
            if (!schema.getVersions().stream().allMatch(SchemaVersion::isDraft)) {
                throw new NoApplicableWorkflowException("This schema has published versions; retire it instead");
            }
        } else {
            throw SchemaContent.unsupportedTarget(HANDLER_NAME, target);
        }
        final Set<String> referrers = referrers(target);
        if (!referrers.isEmpty()) {
            throw new NoApplicableWorkflowException("This cannot be discarded while it is referenced from "
                + String.join(", ", referrers));
        }
        // Removing a child is a change to its parent, which must be writable
        SchemaContent.checkOut(Objects.requireNonNull(target.getParent(), "Schemas live under /Schemas"));
        context.getResourceResolver().delete(target);
    }

    /**
     * Where anything outside the subtree points into it.
     *
     * @param root the subtree about to be deleted
     * @return the paths of the referring nodes, sorted
     * @throws PersistenceException when the references cannot be read
     */
    private Set<String> referrers(final Resource root) throws PersistenceException
    {
        final Node node = Objects.requireNonNull(root.adaptTo(Node.class), "Schemas are stored in a JCR repository");
        final Set<String> referrers = new TreeSet<>();
        try {
            collect(node, node.getPath() + "/", referrers);
        } catch (final RepositoryException e) {
            throw new PersistenceException("Cannot check what refers to " + root.getPath(), e);
        }
        return referrers;
    }

    private void collect(final Node node, final String inside, final Set<String> referrers)
        throws RepositoryException
    {
        if (node.isNodeType("mix:referenceable")) {
            addOutside(node.getReferences(), inside, referrers);
            addOutside(node.getWeakReferences(), inside, referrers);
        }
        final NodeIterator children = node.getNodes();
        while (children.hasNext()) {
            collect(children.nextNode(), inside, referrers);
        }
    }

    private void addOutside(final PropertyIterator references, final String inside, final Set<String> referrers)
        throws RepositoryException
    {
        while (references.hasNext()) {
            final Property reference = references.nextProperty();
            final String holder = reference.getParent().getPath();
            if (!(holder + "/").startsWith(inside)) {
                referrers.add(holder);
            }
        }
    }
}
