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

package io.uhndata.iap.scripting;

import javax.script.Bindings;

import org.apache.sling.api.SlingJakartaHttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for {@link ContentTypeSetter}.
 *
 * @version $Id$
 * @since 0.1.0
 */
public class ContentTypeSetterTest
{
    private SlingJakartaHttpServletResponse response;

    private ContentTypeSetter contentTypeSetter;

    @BeforeEach
    public void setup()
    {
        this.response = Mockito.mock(SlingJakartaHttpServletResponse.class);
        final Bindings bindings = Mockito.mock(Bindings.class);
        Mockito.when(bindings.get("jakartaResponse")).thenReturn(this.response);
        this.contentTypeSetter = new ContentTypeSetter();
        this.contentTypeSetter.init(bindings);
    }

    @Test
    public void testHtml()
    {
        this.contentTypeSetter.html();
        Mockito.verify(this.response).setContentType("text/html;charset=UTF-8");
    }

    @Test
    public void testJavascript()
    {
        this.contentTypeSetter.javascript();
        Mockito.verify(this.response).setContentType("application/javascript;charset=UTF-8");
    }

    @Test
    public void testJson()
    {
        this.contentTypeSetter.json();
        Mockito.verify(this.response).setContentType("application/json;charset=UTF-8");
    }

    @Test
    public void testCsv()
    {
        this.contentTypeSetter.csv();
        Mockito.verify(this.response).setContentType("text/csv;charset=UTF-8");
    }

    @Test
    public void testText()
    {
        this.contentTypeSetter.text();
        Mockito.verify(this.response).setContentType("text/plain;charset=UTF-8");
    }

    @Test
    public void testMarkdown()
    {
        this.contentTypeSetter.markdown();
        Mockito.verify(this.response).setContentType("text/markdown;charset=UTF-8");
    }
}
