package com.tune2ai.studio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KnowledgeConnectorTest {
    @Test
    public void htmlLosesTagsScriptsAndEntities() {
        String text = KnowledgeConnector.stripTags(
                "<html><head><style>p{color:red}</style><script>alert(1)</script></head><body><p>Fish &amp; chips</p><p>&lt;3</p></body></html>");
        assertTrue(text.contains("Fish & chips"));
        assertTrue(text.contains("<3"));
        assertFalse(text.contains("alert"));
        assertFalse(text.contains("color:red"));
        assertFalse(text.contains("<p>"));
    }

    @Test
    public void supportedTypesIncludeDocumentsButNotMedia() {
        assertTrue(KnowledgeConnector.supported("Report.PDF", null));
        assertTrue(KnowledgeConnector.supported("notes.md", null));
        assertTrue(KnowledgeConnector.supported("letter.docx", null));
        assertTrue(KnowledgeConnector.supported("README", "text/plain"));
        assertFalse(KnowledgeConnector.supported("holiday.jpg", "image/jpeg"));
        assertFalse(KnowledgeConnector.supported("song.mp3", null));
    }

    @Test
    public void extensionIsLowercasedAndOptional() {
        assertEquals("md", KnowledgeConnector.extension("Notes.MD"));
        assertEquals("docx", KnowledgeConnector.extension("a.b.docx"));
        assertEquals("", KnowledgeConnector.extension("README"));
    }
}
