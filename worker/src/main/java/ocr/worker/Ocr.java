package ocr.worker;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import net.sourceforge.tess4j.Tesseract;

/**
 * OWNER: Partner B.
 * Downloads an image and runs Tesseract (tess4j) on it.
 */
public class Ocr {

    public Ocr() {
        // TODO(B): net.sourceforge.tess4j.Tesseract t = new Tesseract(); t.setDatapath(<tessdata path>); t.setLanguage("eng");
    }

    /** @throws Exception on download or OCR failure (the Worker turns it into an error result). */
    public String recognize(String url) throws Exception {
        // TODO(B): ImageIO.read(new URL(url)) with timeouts; null image -> throw; return tesseract.doOCR(image)
        throw new UnsupportedOperationException("TODO(B): OCR");
    }

    /** Downloads the URL and decodes it as an image. Any failure throws, and Worker reports it. */
    private BufferedImage loadImage(String url) throws Exception {
        URLConnection conn = new URL(url).openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; OCR-student-project/1.0)");
        conn.setConnectTimeout(10_000); 
        conn.setReadTimeout(30_000);   
        try (InputStream in = conn.getInputStream()){ 
            BufferedImage img = ImageIO.read(in);
            if (img == null){
                throw new Exception("Not an image"); // ImageIO returns null
            } 
            return img;
        }
    }

    public static void main(String[] args) throws Exception {
        BufferedImage img = new Ocr().loadImage(args[0]);
        System.out.println("OK " + img.getWidth() + "x" + img.getHeight());
    }
}
