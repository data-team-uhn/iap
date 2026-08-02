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
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonString;
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

        final JsonObjectBuilder jsonBuilder = Json.createObjectBuilder();
        this.processor.leave(file, jsonBuilder, null);
        final JsonObject json = jsonBuilder.build();

        Assertions.assertEquals("application/pdf", json.getString("jcr:mimeType"));
        Assertions.assertEquals(12345L, json.getJsonNumber("size").longValue());
        Assertions.assertEquals("1970-01-01T00:00:00.000+00:00", json.getString("jcr:lastModified"));
    }

    @Test
    public void ignoresNullOutput() throws Exception
    {
        final Node file = Mockito.mock(Node.class);
        this.processor.leave(file, null, null);
        Mockito.verifyNoInteractions(file);
    }

    @Test
    public void serializesFileContentAsDownloadPath() throws Exception
    {
        final Node content = Mockito.mock(Node.class);
        Mockito.when(content.getName()).thenReturn("jcr:content");
        Mockito.when(content.isNodeType("nt:resource")).thenReturn(true);
        Mockito.when(content.getPath()).thenReturn("/Submissions/s1/doc/consent.pdf/jcr:content");
        JsonValue json = this.processor.processChild(null, content, null, null);
        Assertions.assertEquals("/Submissions/s1/doc/consent.pdf/jcr:content", ((JsonString) json).getString());
    }

    @Test
    public void ignoresNonFiles() throws Exception
    {
        final Node bareFile = mockFile("/file.pdf/jcr:content", "jcr:content");
        JsonObject json = (JsonObject) this.processor.processChild(null, bareFile, null, null);
        Assertions.assertNull(json);
    }

    @Test
    public void ignoresAlreadySerializedNodes() throws Exception
    {
        final Node content = Mockito.mock(Node.class);
        Mockito.when(content.getName()).thenReturn("jcr:content");
        Mockito.when(content.isNodeType("nt:resource")).thenReturn(true);
        Mockito.when(content.getPath()).thenReturn("/Submissions/s1/doc/consent.pdf/jcr:content");
        JsonValue input = Json.createValue("X");
        JsonValue json = this.processor.processChild(null, content, input, null);
        Assertions.assertSame(input, json);
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
        Mockito.when(child.getName()).thenThrow(new RepositoryException("Inaccessible"));
        Assertions.assertNull(this.processor.processChild(null, child, null, null));
    }

    @Test
    public void repositoryErrorsAreCaught() throws Exception
    {
        final Node file = Mockito.mock(Node.class);
        final JsonObjectBuilder jsonBuilder = Json.createObjectBuilder();
        Mockito.when(file.isNodeType("nt:file")).thenThrow(new RepositoryException("Inaccessible"));
        this.processor.leave(file, jsonBuilder, null);
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
