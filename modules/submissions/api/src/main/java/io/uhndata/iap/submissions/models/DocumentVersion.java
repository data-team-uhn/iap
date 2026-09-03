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
package io.uhndata.iap.submissions.models;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.jetbrains.annotations.Nullable;

import io.uhndata.iap.entities.models.EntityPart;

/**
 * A Sling Model wrapping a {@code sub:DocumentVersion} node: one revision of a {@link Document}, holding exactly
 * one uploaded file and everything the parsing pipeline made of it. Uploading a replacement adds a version
 * instead of overwriting the file a reviewer has already read.
 *
 * @version $Id$
 * @since 0.1.0
 */
@Model(adaptables = Resource.class, resourceType = DocumentVersion.RESOURCE_TYPE,
    defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL)
public class DocumentVersion extends EntityPart
{
    /** The {@code sling:resourceType} of a {@code sub:DocumentVersion} node. */
    public static final String RESOURCE_TYPE = "sub/DocumentVersion";

    /** The name of the child node holding this revision's file. */
    private static final String FILE_CHILD = "file";

    /**
     * The file this revision consists of.
     *
     * @return a file, or {@code null} while the upload has not landed yet
     */
    @Nullable
    public File getFile()
    {
        return this.getChild(FILE_CHILD, File.RESOURCE_TYPE, File.class);
    }

    /**
     * This revision's number, counting from 1. Not stored: the versions of a document are kept in a deliberate
     * chronological order, so a revision's position in that list is its number.
     *
     * @return a 1-based revision number, or 0 if this node does not sit under a document
     */
    public int getNumber()
    {
        final Document document = this.getParent(Document.RESOURCE_TYPE, Document.class);
        if (document == null) {
            return 0;
        }
        return document.getVersions().stream()
            .map(DocumentVersion::getPath)
            .toList()
            .indexOf(this.getPath()) + 1;
    }
}
