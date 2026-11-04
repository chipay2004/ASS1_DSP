package ocr.worker;

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
}
