/*
 * Licensed to the Technische Universität Darmstadt under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The Technische Universität Darmstadt 
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.
 *  
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.inception.pdfeditor2.visual.model;

import java.util.List;

public class VLine
{
    private final List<VGlyph> glyphs;
    private final int begin;
    private final int end;
    private final String text;

    public VLine(int aBegin, int aEnd, String aText, List<VGlyph> aGlyphs)
    {
        begin = aBegin;
        end = aEnd;
        text = aText;
        glyphs = aGlyphs;
    }

    public List<VGlyph> getGlyphs()
    {
        return glyphs;
    }

    public int getBegin()
    {
        return begin;
    }

    public int getEnd()
    {
        return end;
    }

    public String getText()
    {
        return text;
    }
}
