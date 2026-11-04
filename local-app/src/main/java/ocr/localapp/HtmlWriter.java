package ocr.localapp;

import ocr.common.Messages.ImageResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** OWNER: Partner B. Turns the summary into the output HTML (match the sample output file). */
public final class HtmlWriter {
    private HtmlWriter() {}

    public static void write(List<ImageResult> results, Path output) throws IOException {
        StringBuilder html = new StringBuilder("<html><head><meta charset=\"UTF-8\"><title>OCR</title></head><body>\n");
        for (ImageResult r : results) {
            // TODO(B): match the sample output layout exactly
            if (r.error == null) {
                html.append("<p><img src=\"").append(escape(r.url)).append("\"><br>")
                    .append("<a href=\"").append(escape(r.url)).append("\">").append(escape(r.url)).append("</a><br>")
                    .append(escape(r.text)).append("</p>\n");
            } else {
                html.append("<p><a href=\"").append(escape(r.url)).append("\">").append(escape(r.url)).append("</a> ")
                    .append(escape(r.error)).append("</p>\n");
            }
        }
        html.append("</body></html>\n");
        Files.writeString(output, html.toString());
    }

    /** OCR text can contain < > & " — always escape. */
    static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
