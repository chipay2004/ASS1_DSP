package ocr.localapp;

import ocr.common.Messages.ImageResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** OWNER: Partner B. Turns the summary into the output HTML (match the sample output file). */
public final class HtmlWriter {
    private HtmlWriter() {}

    public static void write(List<ImageResult> results, Path output) throws IOException{
        StringBuilder html = new StringBuilder("<html><head><meta charset=\"UTF-8\"><title>OCR</title></head><body>\n");
        for (ImageResult r : results){
            // TODO(B): match the sample output layout exactly
            if (r.error == null){
                html.append("<p>\n\t<img src=\"").append(escape(r.url)).append("\"><br>\n\t")
                    .append(escape(r.text)).append("\n</p>\n");
            }else{
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


    // TEMP test, delete later. Builds fake results incl. nasty text and a failure.
    public static void main(String[] args) throws IOException {
        ImageResult a = new ImageResult();
    a.url = "http://example.com/a.png"; a.text = "3D Text";
        ImageResult b = new ImageResult();
        b.url = "http://example.com/b.png"; b.text = "5 < 6 & \"quotes\" <script>";
        ImageResult c = new ImageResult();
        c.url = "http://example.com/c.png"; c.error = "FileNotFoundException: 404";
        write(List.of(a, b, c), Path.of("test-output.html"));
        System.out.println("wrote test-output.html");
    }
}
