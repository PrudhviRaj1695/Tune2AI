package com.tune2ai.studio;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Downloads one catalog model over HTTPS into a {@code .partial} file, resuming a partial file
 * from an earlier attempt, and renames it to its final name once the size matches. Plain Java so
 * it stays free of Android types; the caller runs it on a background thread.
 *
 * <p>ponytail: runs while Studio is open (the screen is kept on during a download). Move it to a
 * foreground service or WorkManager if downloads must survive the app being closed.
 */
final class ModelDownloader {
    interface Listener {
        void progress(long receivedBytes, long totalBytes);
    }

    static final class CancelledException extends IOException {
        CancelledException() {
            super("Download cancelled");
        }
    }

    private static final int PROGRESS_INTERVAL_MS = 250;

    private ModelDownloader() {}

    /** Returns the downloaded file, already renamed to {@code dir/entry.fileName}. */
    static File download(ModelAdvisor.Entry entry, File dir, AtomicBoolean cancelled, Listener listener) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Could not create the models folder");
        File target = new File(dir, entry.fileName);
        File partial = new File(dir, entry.fileName + ".partial");
        long have = partial.length();
        if (have > entry.sizeBytes) {
            partial.delete();
            have = 0;
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(entry.url).openConnection();
        try {
            // Hugging Face answers with a redirect to its CDN; same-protocol redirects are followed.
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(20_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("User-Agent", "Tune2AI-Studio");
            if (have > 0 && have < entry.sizeBytes) conn.setRequestProperty("Range", "bytes=" + have + "-");
            int code = conn.getResponseCode();
            boolean append;
            if (have >= entry.sizeBytes) {
                append = true; // already complete; fall through to verification
            } else if (code == HttpURLConnection.HTTP_PARTIAL && have > 0) {
                append = true;
            } else if (code == HttpURLConnection.HTTP_OK) {
                append = false;
                have = 0;
            } else {
                throw new IOException("The server answered " + code);
            }

            if (have < entry.sizeBytes) {
                try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(partial, append)) {
                    byte[] buffer = new byte[1 << 16];
                    long lastReport = 0;
                    listener.progress(have, entry.sizeBytes);
                    for (int n; (n = in.read(buffer)) > 0; ) {
                        if (cancelled.get()) throw new CancelledException();
                        out.write(buffer, 0, n);
                        have += n;
                        long now = System.currentTimeMillis();
                        if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                            lastReport = now;
                            listener.progress(have, entry.sizeBytes);
                        }
                    }
                }
            }
        } finally {
            conn.disconnect();
        }

        if (partial.length() != entry.sizeBytes) {
            if (partial.length() > entry.sizeBytes) partial.delete();
            throw new IOException("The download stopped early. Tap Download to resume.");
        }
        listener.progress(entry.sizeBytes, entry.sizeBytes);
        if (!partial.renameTo(target)) throw new IOException("Could not save the model file");
        return target;
    }
}
