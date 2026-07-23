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

package io.uhndata.iap.serialization.internal;

import java.util.Calendar;
import java.util.TimeZone;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.nodetype.NodeType;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for {@link FilesProcessor}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class FilesProcessorTest
{
    private FilesProcessor processor;

    @BeforeEach
    public void setup()
    {
        this.processor = new FilesProcessor();
    }

    @Test
    public void isNamedFilesAndEnabledByDefaultBeforeTheDeepProcessor()
    {
        Assertions.assertEquals("files", this.processor.getName());
        Assertions.assertTrue(this.processor.getPriority() < new DeepProcessor().getPriority());
        Assertions.assertTrue(this.processor.isEnabledByDefault(null));
    }

    @Test
    public void serializesFilesAsDownloadDescriptors() throws Exception
    {
        final Node file = mockFile("/Submissions/s1/doc/consent.pdf", "consent.pdf");
        final Node content = Mockito.mock(Node.class);
        Mockito.when(file.hasNode("jcr:content")).thenReturn(true);
        Mockito.when(file.getNode("jcr:content")).thenReturn(content);
        mockProperty(content, "jcr:mimeType", "application/pdf");
        final Property data = mockProperty(content, "jcr:data", null);
        Mockito.when(data.getLength()).thenReturn(12345L);
        final Property lastModified = mockProperty(content, "jcr:lastModified", null);
        final Calendar date = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        date.setTimeInMillis(0);
        Mockito.when(lastModified.getDate()).thenReturn(date);

        final JsonValue result = this.processor.processChild(null, file, null, null);

        final JsonObject json = (JsonObject) result;
        Assertions.assertEquals("nt:file", json.getString("jcr:primaryType"));
        Assertions.assertEquals("/Submissions/s1/doc/consent.pdf", json.getString("@path"));
        Assertions.assertEquals("consent.pdf", json.getString("@name"));
        Assertions.assertEquals("application/pdf", json.getString("contentType"));
        Assertions.assertEquals(12345L, json.getJsonNumber("size").longValue());
        Assertions.assertEquals("1970-01-01T00:00:00.000Z", json.getString("lastModified"));
    }

    @Test
    public void serializesFilesWithoutContentOrMetadata() throws Exception
    {
        final Node bareFile = mockFile("/f", "f");
        JsonObject json = (JsonObject) this.processor.processChild(null, bareFile, null, null);
        Assertions.assertEquals("/f", json.getString("@path"));
        Assertions.assertFalse(json.containsKey("contentType"));

        final Node emptyContentFile = mockFile("/g", "g");
        final Node content = Mockito.mock(Node.class);
        Mockito.when(emptyContentFile.hasNode("jcr:content")).thenReturn(true);
        Mockito.when(emptyContentFile.getNode("jcr:content")).thenReturn(content);
        json = (JsonObject) this.processor.processChild(null, emptyContentFile, null, null);
        Assertions.assertFalse(json.containsKey("contentType"));
        Assertions.assertFalse(json.containsKey("size"));
        Assertions.assertFalse(json.containsKey("lastModified"));
    }

    @Test
    public void leavesNonFilesAndAlreadySerializedChildrenUntouched() throws Exception
    {
        final Node child = Mockito.mock(Node.class);
        Mockito.when(child.isNodeType("nt:file")).thenReturn(false);
        Assertions.assertNull(this.processor.processChild(null, child, null, null));

        final JsonValue existing = Json.createValue("already serialized");
        Assertions.assertSame(existing, this.processor.processChild(null, child, existing, null));
    }

    @Test
    public void repositoryErrorsLeaveTheChildUnserialized() throws Exception
    {
        final Node child = Mockito.mock(Node.class);
        Mockito.when(child.isNodeType("nt:file")).thenThrow(new RepositoryException("Inaccessible"));
        Assertions.assertNull(this.processor.processChild(null, child, null, null));
    }

    private Node mockFile(final String path, final String name) throws RepositoryException
    {
        final Node file = Mockito.mock(Node.class);
        final NodeType fileType = Mockito.mock(NodeType.class);
        Mockito.when(fileType.getName()).thenReturn("nt:file");
        Mockito.when(file.isNodeType("nt:file")).thenReturn(true);
        Mockito.when(file.getPrimaryNodeType()).thenReturn(fileType);
        Mockito.when(file.getPath()).thenReturn(path);
        Mockito.when(file.getName()).thenReturn(name);
        return file;
    }

    private Property mockProperty(final Node parent, final String name, final String value)
        throws RepositoryException
    {
        final Property property = Mockito.mock(Property.class);
        Mockito.when(parent.hasProperty(name)).thenReturn(true);
        Mockito.when(parent.getProperty(name)).thenReturn(property);
        if (value != null) {
            Mockito.when(property.getString()).thenReturn(value);
        }
        return property;
    }
}
