package ocr.worker;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import net.sourceforge.tess4j.Tesseract;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.DataBufferByte;
import java.nio.ByteBuffer;

/**
 * OWNER: Partner B.
 * Downloads an image and runs Tesseract (tess4j) on it.
 */
public class Ocr {

    // The Worker handles one message at a time, so one instance is enough.
    private final Tesseract tesseract = new Tesseract();

    public Ocr() {
        String dir = System.getenv("TESSDATA_PREFIX");
        if (dir != null && !dir.isBlank()){
            tesseract.setDatapath(dir);
        } 
        tesseract.setLanguage("eng");
    }

    /** @throws Exception on download or OCR failure (the Worker turns it into an error result). */
    public String recognize(String url) throws Exception{
        BufferedImage img = loadImage(url);
        return tesseract.doOCR(img.getWidth(), img.getHeight(), toGrayBuffer(img), 8, null, null).trim();
    }
    
    /** Downloads the URL and decodes it as an image. Any failure throws, and Worker reports it. */
    private BufferedImage loadImage(String url) throws Exception{
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

    /** Converts any image to 8-bit grayscale pixels in a direct buffer, ready for Tesseract's raw-pixel API. */
    private static ByteBuffer toGrayBuffer(BufferedImage img) {
        BufferedImage gray = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = gray.createGraphics();
        g.setColor(Color.WHITE);                       // transparent PNG pixels would turn black; white keeps text readable
        g.fillRect(0, 0, gray.getWidth(), gray.getHeight());
        g.drawImage(img, 0, 0, null);
        g.dispose();
        byte[] px = ((DataBufferByte) gray.getRaster().getDataBuffer()).getData();
        ByteBuffer buf = ByteBuffer.allocateDirect(px.length); // direct buffer: required to pass memory to native code
        buf.put(px);
        buf.flip();
        return buf;
    }

    public static void main(String[] args) throws Exception {
        System.out.println(new Ocr().recognize(args[0]));
    }
}
