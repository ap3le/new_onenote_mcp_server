package com.onenote.parser;

import org.apache.tika.metadata.Metadata;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * CLI entry point for parsing OneNote .one files.
 *
 * Usage:
 *   java -jar onenote-parser.jar file.one                  — parse and display pages
 *   java -jar onenote-parser.jar --debug file.one           — show raw XHTML from Tika
 *   java -jar onenote-parser.jar --text file.one            — show plain text
 *   java -jar onenote-parser.jar --metadata file.one        — show document metadata
 *   java -jar onenote-parser.jar --pathfile path.txt        — read file path from a UTF-8 text file
 */
public class Main {

    public static void main(String[] args) {
        // Force UTF-8 output
        try {
            System.setOut(new PrintStream(System.out, true, "UTF-8"));
            System.setErr(new PrintStream(System.err, true, "UTF-8"));
        } catch (Exception ignored) {}

        if (args.length < 1) {
            printUsage();
            System.exit(1);
        }

        try {
            String mode = "parse";
            String filePath;

            if (args[0].startsWith("--")) {
                if (args.length < 2) {
                    printUsage();
                    System.exit(1);
                }
                mode = args[0].substring(2);
                filePath = args[1];
            } else {
                filePath = args[0];
            }

            // --pathfile mode: read the actual path from a UTF-8 text file
            if ("pathfile".equals(mode)) {
                String pathFileContent = new String(
                    Files.readAllBytes(Paths.get(filePath)), StandardCharsets.UTF_8).trim();
                // Strip BOM if present
                if (pathFileContent.startsWith("\uFEFF")) {
                    pathFileContent = pathFileContent.substring(1);
                }
                // First line may be the mode, rest is the path  
                String[] lines = pathFileContent.split("\\r?\\n");
                if (lines.length >= 2) {
                    mode = lines[0].trim();
                    filePath = lines[1].trim();
                } else {
                    mode = "parse";
                    filePath = lines[0].trim();
                }
            }

            File file = new File(filePath);
            if (!file.exists()) {
                System.err.println("Error: file not found — " + filePath);
                System.exit(1);
            }

            OneNoteFileParser parser = new OneNoteFileParser();

            switch (mode) {
                case "debug":
                    System.out.println("=== Raw XHTML Output ===");
                    System.out.println(parser.getRawXhtml(file));
                    break;

                case "text":
                    System.out.println("=== Plain Text ===");
                    System.out.println(parser.getPlainText(file));
                    break;

                case "metadata":
                    System.out.println("=== Document Metadata ===");
                    Metadata metadata = parser.getMetadata(file);
                    for (String name : metadata.names()) {
                        System.out.printf("  %s: %s%n", name, metadata.get(name));
                    }
                    break;

                case "parse":
                default:
                    List<OneNotePage> pages = parser.parse(file);
                    System.out.printf("Found %d page(s) in: %s%n%n", pages.size(), file.getName());
                    for (OneNotePage page : pages) {
                        System.out.println(page);
                        System.out.println("---");
                    }
                    break;
            }

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("OneNote .one File Parser (Apache Tika)");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -jar onenote-parser.jar <file.one>              Parse and display pages");
        System.out.println("  java -jar onenote-parser.jar --debug <file.one>      Show raw XHTML from Tika");
        System.out.println("  java -jar onenote-parser.jar --text <file.one>       Show concatenated plain text");
        System.out.println("  java -jar onenote-parser.jar --metadata <file.one>   Show document metadata");
    }
}
