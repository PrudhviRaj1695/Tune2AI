package com.tune2ai.studio;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import com.tune2ai.sdk.api.KnowledgeApi;
import com.tune2ai.sdk.api.KnowledgeSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Connects things on the phone to the SDK's knowledge index: folders and files picked through the
 * system file picker (Storage Access Framework, so Studio needs no storage permission), text and
 * files shared from other apps, and typed notes. Reads text out of each document and hands it to
 * {@link KnowledgeApi}. Call every method from a background thread.
 *
 * <p>PDF text comes from the platform's PdfRenderer on Android 15+ (API 35); older versions skip
 * PDFs. ponytail: add a PDF parser dependency behind {@link #extractText} if older phones need them.
 */
final class KnowledgeConnector {
    interface Progress {
        void update(int done, int total, String current);
    }

    /** What one indexing run did. */
    static final class Summary {
        int added;
        int unchanged;
        int skipped;
        int failed;
        int pdfSkipped;
        String lastError;

        void add(Summary other) {
            added += other.added;
            unchanged += other.unchanged;
            skipped += other.skipped;
            failed += other.failed;
            pdfSkipped += other.pdfSkipped;
            if (other.lastError != null) lastError = other.lastError;
        }
    }

    /** Platform PDF text extraction (PdfRenderer.Page.getTextContents) exists from Android 15. */
    static boolean pdfSupported() {
        return android.os.Build.VERSION.SDK_INT >= 35;
    }
    private static final int MAX_PDF_PAGES = 300;

    static final long MAX_FILE_BYTES = 4L * 1024 * 1024;
    static final int MAX_CHARS = 400_000; // about 500 chunks; keeps one file's indexing under a minute
    private static final int MAX_FILES_PER_FOLDER = 2_000;
    private static final String PREFS = "knowledge";
    private static final String FOLDERS = "folders";

    private static final Set<String> TEXT_EXTENSIONS = new HashSet<>(java.util.Arrays.asList(
            "txt", "md", "markdown", "csv", "tsv", "json", "xml", "html", "htm", "log", "yaml", "yml", "ini", "conf",
            "cfg", "toml", "rst", "tex", "srt", "vtt", "java", "kt", "kts", "py", "js", "ts", "tsx", "jsx", "c", "h",
            "cpp", "hpp", "cs", "go", "rs", "swift", "rb", "php", "sh", "sql", "gradle", "properties", "docx"));

    private final Context context;
    private final KnowledgeApi knowledge;
    private final SharedPreferences prefs;

    KnowledgeConnector(Context context, KnowledgeApi knowledge) {
        this.context = context.getApplicationContext();
        this.knowledge = knowledge;
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------- Folders ----------

    /** Connected folder tree URIs, in the order they were added. */
    List<String> folders() {
        List<String> out = new ArrayList<>();
        for (String f : prefs.getString(FOLDERS, "").split("\n")) {
            if (!f.isEmpty() && !out.contains(f)) out.add(f);
        }
        return out;
    }

    /** Keeps read access to the folder across restarts and indexes it. */
    Summary addFolder(Uri tree, Progress progress) {
        context.getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        List<String> folders = folders();
        if (!folders.contains(tree.toString())) {
            folders.add(tree.toString());
            prefs.edit().putString(FOLDERS, String.join("\n", folders)).apply();
        }
        return syncFolder(tree, progress);
    }

    /** Re-reads every connected folder: changed files are re-indexed, deleted ones removed. */
    Summary refreshFolders(Progress progress) {
        Summary total = new Summary();
        for (String folder : folders()) total.add(syncFolder(Uri.parse(folder), progress));
        return total;
    }

    /** Disconnects a folder and removes everything indexed from it. */
    void removeFolder(String tree) {
        List<String> folders = folders();
        folders.remove(tree);
        prefs.edit().putString(FOLDERS, String.join("\n", folders)).apply();
        for (KnowledgeSource s : knowledge.sourcesBlocking()) {
            if (tree.equals(s.getMetadata().get("folder"))) knowledge.removeBlocking(s.getId());
        }
        try {
            context.getContentResolver().releasePersistableUriPermission(Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // permission already gone
        }
    }

    static String folderName(Uri tree) {
        String id = DocumentsContract.getTreeDocumentId(tree);
        int colon = id.lastIndexOf(':');
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        if (path.isEmpty()) return "Internal storage";
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private Summary syncFolder(Uri tree, Progress progress) {
        List<Doc> docs = new ArrayList<>();
        try {
            walk(tree, DocumentsContract.getTreeDocumentId(tree), "", docs, progress);
        } catch (RuntimeException e) {
            Summary summary = new Summary();
            summary.failed++;
            summary.lastError = "Could not read " + folderName(tree) + ": " + e.getMessage();
            return summary;
        }
        return syncDocs(tree.toString(), null, docs, progress);
    }

    /**
     * Indexes [docs] as the contents of one scope (a picked folder, or internal storage): new and
     * changed files are (re)indexed, unchanged ones skipped, and files no longer present removed.
     */
    private Summary syncDocs(String scope, String connector, List<Doc> docs, Progress progress) {
        Summary summary = new Summary();
        Map<String, KnowledgeSource> existing = new HashMap<>();
        for (KnowledgeSource s : knowledge.sourcesBlocking()) {
            if (scope.equals(s.getMetadata().get("folder"))) existing.put(s.getId(), s);
        }
        Set<String> seen = new HashSet<>();
        int done = 0;
        for (Doc doc : docs) {
            progress.update(done++, docs.size(), doc.name);
            String id = doc.uri.toString();
            seen.add(id);
            KnowledgeSource old = existing.get(id);
            if (old != null && String.valueOf(doc.modified).equals(old.getMetadata().get("modified"))) { // unchanged
                summary.unchanged++;
                continue;
            }
            Map<String, String> meta = new HashMap<>();
            meta.put("folder", scope);
            meta.put("path", doc.path);
            meta.put("modified", String.valueOf(doc.modified));
            meta.put("kind", "file");
            if (connector != null) meta.put("connector", connector);
            index(doc.uri, doc.name, doc.mime, doc.size, meta, summary);
        }
        for (String id : existing.keySet()) {
            if (!seen.contains(id)) knowledge.removeBlocking(id); // deleted since the last sync
        }
        progress.update(docs.size(), docs.size(), "");
        return summary;
    }

    // ---------- Internal storage (All files access) ----------

    static final String STORAGE_SCOPE = "storage";
    private static final int MAX_STORAGE_FILES = 3_000;
    private static final Set<String> SKIP_DIRS = new HashSet<>(java.util.Arrays.asList(
            "Android", "DCIM", "Pictures", "Movies", "Music", "Ringtones", "Alarms", "Notifications", "Podcasts", "Recordings"));

    /** Indexes supported files anywhere in shared storage. Needs All files access (Android 11+). */
    Summary syncStorage(Progress progress) {
        java.io.File root = android.os.Environment.getExternalStorageDirectory();
        List<Doc> docs = new ArrayList<>();
        walkFiles(root, "", docs, progress, 0);
        return syncDocs(STORAGE_SCOPE, "storage", docs, progress);
    }

    /** Removes everything indexed from internal storage. */
    void clearStorage() {
        for (KnowledgeSource s : knowledge.sourcesBlocking()) {
            if (STORAGE_SCOPE.equals(s.getMetadata().get("folder"))) knowledge.removeBlocking(s.getId());
        }
    }

    private void walkFiles(java.io.File dir, String path, List<Doc> out, Progress progress, int depth) {
        java.io.File[] children = dir.listFiles();
        if (children == null || depth > 8) return;
        for (java.io.File f : children) {
            if (out.size() >= MAX_STORAGE_FILES) return;
            String name = f.getName();
            if (name.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (depth == 0 && SKIP_DIRS.contains(name)) continue; // media and app-private areas
                walkFiles(f, path + name + "/", out, progress, depth + 1);
            } else if (supported(name, null)) {
                out.add(new Doc(Uri.fromFile(f), name, path + name, null, f.length(), f.lastModified()));
                if (out.size() % 25 == 0) progress.update(0, 0, "Scanning… " + out.size() + " files found");
            }
        }
    }

    private static final class Doc {
        final Uri uri;
        final String name;
        final String path;
        final String mime;
        final long size;
        final long modified;

        Doc(Uri uri, String name, String path, String mime, long size, long modified) {
            this.uri = uri;
            this.name = name;
            this.path = path;
            this.mime = mime;
            this.size = size;
            this.modified = modified;
        }
    }

    private void walk(Uri tree, String parentId, String path, List<Doc> out, Progress progress) {
        if (out.size() >= MAX_FILES_PER_FOLDER) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] cols = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        };
        try (Cursor c = context.getContentResolver().query(children, cols, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext() && out.size() < MAX_FILES_PER_FOLDER) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                if (name == null || name.startsWith(".")) continue; // hidden files and folders
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    walk(tree, id, path + name + "/", out, progress);
                } else {
                    out.add(new Doc(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, path + name, mime,
                            c.isNull(3) ? -1 : c.getLong(3), c.isNull(4) ? 0 : c.getLong(4)));
                    // Large folders take a while to list; show that something is happening.
                    if (out.size() % 25 == 0) progress.update(0, 0, "Scanning… " + out.size() + " files found");
                }
            }
        }
    }

    // ---------- Files and shares ----------

    /** Indexes picked or shared files. Picked files keep access across restarts when the provider allows it. */
    Summary addFiles(List<Uri> uris, boolean persist, String kind, Progress progress) {
        Summary summary = new Summary();
        ContentResolver resolver = context.getContentResolver();
        int done = 0;
        for (Uri uri : uris) {
            String name = displayName(uri);
            progress.update(done++, uris.size(), name);
            if (persist) {
                try {
                    resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {
                    // provider does not offer persistable access; the indexed text is still kept
                }
            }
            Map<String, String> meta = new HashMap<>();
            meta.put("kind", kind);
            index(uri, name, resolver.getType(uri), size(uri), meta, summary);
        }
        progress.update(uris.size(), uris.size(), "");
        return summary;
    }

    /** Adds typed or shared text as its own source. */
    KnowledgeSource addNote(String title, String text, String kind) {
        String name = title == null || title.trim().isEmpty() ? firstLine(text) : title.trim();
        Map<String, String> meta = new HashMap<>();
        meta.put("kind", kind);
        return knowledge.addBlocking(kind + ":" + System.currentTimeMillis(), name, text, meta);
    }

    private void index(Uri uri, String name, String mime, long size, Map<String, String> meta, Summary summary) {
        String ext = extension(name);
        if ("pdf".equals(ext) && !pdfSupported()) {
            summary.pdfSkipped++;
            return;
        }
        if (!supported(name, mime) || (size > MAX_FILE_BYTES && !"pdf".equals(ext)) || size > 4 * MAX_FILE_BYTES) {
            summary.skipped++;
            return;
        }
        try {
            String text = extractText(uri, ext);
            if (text.trim().isEmpty()) {
                summary.skipped++;
                return;
            }
            meta.put("bytes", String.valueOf(size));
            if (mime != null) meta.put("mime", mime);
            knowledge.addBlocking(uri.toString(), name, text, meta);
            summary.added++;
        } catch (IOException | RuntimeException e) {
            summary.failed++;
            summary.lastError = name + ": " + e.getMessage();
        }
    }

    static boolean supported(String name, String mime) {
        String ext = extension(name);
        return TEXT_EXTENSIONS.contains(ext) || "pdf".equals(ext) || (mime != null && mime.startsWith("text/"));
    }

    /** Plain text from a document: UTF-8 as-is, HTML/XML without tags, DOCX from its document.xml, PDF per page. */
    String extractText(Uri uri, String ext) throws IOException {
        if ("docx".equals(ext)) return docxText(uri);
        if ("pdf".equals(ext)) return pdfText(uri);
        String raw = readUtf8(uri);
        if (ext.equals("html") || ext.equals("htm") || ext.equals("xml")) raw = stripTags(raw);
        return raw.length() > MAX_CHARS ? raw.substring(0, MAX_CHARS) : raw;
    }

    private String readUtf8(Uri uri) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("could not open");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 15];
            for (int n; (n = in.read(buf)) > 0 && out.size() < MAX_FILE_BYTES; ) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String docxText(Uri uri) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri); ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (!"word/document.xml".equals(e.getName())) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[1 << 15];
                for (int n; (n = zip.read(buf)) > 0 && out.size() < 4 * MAX_FILE_BYTES; ) out.write(buf, 0, n);
                String xml = out.toString(StandardCharsets.UTF_8.name()).replace("</w:p>", "\n").replace("<w:tab/>", "\t");
                String text = stripTags(xml);
                return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
            }
        }
        throw new IOException("not a Word document");
    }

    /** Text of each page under a "## Page N" heading, so citations name the page. Android 15+. */
    @android.annotation.TargetApi(35)
    private String pdfText(Uri uri) throws IOException {
        if (!pdfSupported()) throw new IOException("PDF needs Android 15");
        try (android.os.ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (fd == null) throw new IOException("could not open");
            StringBuilder out = new StringBuilder();
            try (android.graphics.pdf.PdfRenderer pdf = new android.graphics.pdf.PdfRenderer(fd)) {
                int pages = Math.min(pdf.getPageCount(), MAX_PDF_PAGES);
                for (int i = 0; i < pages && out.length() < MAX_CHARS; i++) {
                    try (android.graphics.pdf.PdfRenderer.Page page = pdf.openPage(i)) {
                        StringBuilder text = new StringBuilder();
                        for (android.graphics.pdf.content.PdfPageTextContent t : page.getTextContents()) text.append(t.getText()).append('\n');
                        if (text.toString().trim().isEmpty()) continue; // scanned image page: no text layer
                        out.append("## Page ").append(i + 1).append("\n\n").append(text.toString().trim()).append("\n\n");
                    }
                }
            } catch (SecurityException e) {
                throw new IOException("password-protected PDF");
            }
            String text = out.toString();
            return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
        }
    }

    static String stripTags(String s) {
        return s.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replaceAll("[ \\t]+", " ");
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String firstLine(String text) {
        String line = text.trim().split("\n", 2)[0].trim();
        return line.isEmpty() ? "Note" : line.length() > 48 ? line.substring(0, 47) + "…" : line;
    }

    String displayName(Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (RuntimeException ignored) {
            // fall back to the last path segment
        }
        String last = uri.getLastPathSegment();
        return last == null ? "Document" : last.substring(last.lastIndexOf('/') + 1);
    }

    private long size(Uri uri) {
        try (Cursor c = context.getContentResolver().query(uri, new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (RuntimeException ignored) {
            // unknown size
        }
        return -1;
    }
}
