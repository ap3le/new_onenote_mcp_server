import org.apache.tika.parser.microsoft.onenote.OneNoteTitleExtractor;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class TitleTest {
    public static void main(String[] args) throws Exception {
        // Read path from filepath.txt (UTF-8) to handle Hebrew filenames
        String path = new String(Files.readAllBytes(Paths.get("filepath.txt")), StandardCharsets.UTF_8).trim();
        if (path.startsWith("\uFEFF")) path = path.substring(1);
        // If multi-line, take last line as path
        String[] lines = path.split("\\r?\\n");
        path = lines[lines.length - 1].trim();
        
        File f = new File(path);
        System.out.println("Extracting titles from: " + f.getName());
        List<String> titles = OneNoteTitleExtractor.extractTitles(f);
        System.out.println("Found " + titles.size() + " title(s):");
        for (int i = 0; i < titles.size(); i++) {
            System.out.println("  " + (i+1) + ". \"" + titles.get(i) + "\"");
        }
    }
}
