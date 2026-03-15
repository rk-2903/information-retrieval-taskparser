import java.io.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.regex.*;

public class TextParser {

    // Paths relative to project directory
    private static final Path STOPWORD_FILE = Paths.get("stopwordlist.txt");
    private static final Path DOCUMENT_FOLDER = Paths.get("ft911");
    private static final Path OUTPUT_FILE = Paths.get("parser_output.txt");
    private static final String INPUT_FILE_PATTERN = "ft911_*";

    // Data structures
    private final Set<String> stopwordSet = new HashSet<>();
    private final Set<String> termSet = new TreeSet<>();
    private final Map<String, Integer> documentIdMap = new TreeMap<>();

    // Porter Stemmer
    private final Porter porterStemmer = new Porter();

    // Regex Patterns
    private static final Pattern DOC_BLOCK_PATTERN =
            Pattern.compile("(?s)<DOC>.*?<\\/DOC>");

    private static final Pattern DOCNO_PATTERN =
            Pattern.compile("<DOCNO\\s*([^<\\s]+)\\s*<\\/DOCNO>", Pattern.CASE_INSENSITIVE);

    private static final Pattern TEXT_BLOCK_PATTERN =
            Pattern.compile("(?is)<TEXT>(.*?)<\\/TEXT>");

    private static final Pattern TOKEN_SPLIT_PATTERN =
            Pattern.compile("[^A-Za-z0-9]+");

    private static final Pattern DIGIT_PATTERN =
            Pattern.compile(".*\\d.*");

    public static void main(String[] args) {
        try {
            System.out.println("Starting Text Parsing...");
            new TextParser().executeParser();
            System.out.println("Parsing completed successfully.");
        } catch (Exception e) {
            System.err.println("Parser failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    // Main execution pipeline
    private void executeParser() throws IOException {

        // Load stopwords
        loadStopwordFile(STOPWORD_FILE);

        // Find all input files
        List<Path> inputFiles = getInputFiles(DOCUMENT_FOLDER, INPUT_FILE_PATTERN);

        if (inputFiles.isEmpty()) {
            throw new FileNotFoundException(
                    "No files matching '" + INPUT_FILE_PATTERN + "' found in folder 'ft911'.");
        }

        // Process each file
        for (Path filePath : inputFiles) {

            String fileContent = Files.readString(filePath, StandardCharsets.UTF_8);

            Matcher docMatcher = DOC_BLOCK_PATTERN.matcher(fileContent);

            while (docMatcher.find()) {

                String documentBlock = docMatcher.group();

                String documentName = extractDocumentName(documentBlock);

                if (documentName == null)
                    continue;

                Integer documentId = parseDocumentId(documentName);

                if (documentId == null)
                    continue;

                documentIdMap.put(documentName, documentId);

                // Extract text from TEXT tags
                StringBuilder combinedText = new StringBuilder();

                Matcher textMatcher = TEXT_BLOCK_PATTERN.matcher(documentBlock);

                while (textMatcher.find()) {
                    combinedText.append(textMatcher.group(1)).append(' ');
                }

                // Tokenization + preprocessing
                for (String token : TOKEN_SPLIT_PATTERN.split(combinedText.toString())) {

                    if (token.isEmpty())
                        continue;

                    String normalizedToken = token.toLowerCase(Locale.ROOT);

                    if (DIGIT_PATTERN.matcher(normalizedToken).matches())
                        continue;

                    if (stopwordSet.contains(normalizedToken))
                        continue;

                    String stemmedToken = applyStemming(normalizedToken);

                    if (!stemmedToken.isEmpty()) {
                        termSet.add(stemmedToken);
                    }
                }
            }
        }

        // Assign term IDs
        Map<String, Integer> termDictionary = new LinkedHashMap<>();

        int termId = 1;

        for (String term : termSet) {
            termDictionary.put(term, termId++);
        }

        // Write final output
        writeParserOutput(termDictionary, documentIdMap, OUTPUT_FILE);
    }

    // Load stopword list
    private void loadStopwordFile(Path stopwordPath) throws IOException {

        try (BufferedReader reader =
                     Files.newBufferedReader(stopwordPath, StandardCharsets.UTF_8)) {

            String line;

            while ((line = reader.readLine()) != null) {

                String word = line.trim().toLowerCase(Locale.ROOT);

                if (!word.isEmpty())
                    stopwordSet.add(word);
            }
            System.out.println("Stop word Count: " + stopwordSet.size());
        }
    }

    // Retrieve input files
    private List<Path> getInputFiles(Path directory, String filePattern) throws IOException {

        List<Path> fileList = new ArrayList<>();

        try (DirectoryStream<Path> stream =
                     Files.newDirectoryStream(directory, filePattern)) {

            for (Path path : stream) {

                if (Files.isRegularFile(path)) {
                    fileList.add(path);
                }
            }
        }

        fileList.sort(Comparator.naturalOrder());

        return fileList;
    }

    // Extract DOCNO
    private String extractDocumentName(String documentBlock) {

        Matcher matcher = DOCNO_PATTERN.matcher(documentBlock);

        return matcher.find() ? matcher.group(1).trim() : null;
    }

    // Extract numeric document ID
    private Integer parseDocumentId(String documentName) {

        int dashIndex = documentName.lastIndexOf('-');

        if (dashIndex <= 0 || dashIndex == documentName.length() - 1)
            return null;

        String numericPart = documentName.substring(dashIndex + 1);

        try {
            return Integer.parseInt(numericPart);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // Apply Porter stemming
    private String applyStemming(String token) {

        try {
            return porterStemmer.stripAffixes(token);
        } catch (Throwable t) {
            return token;
        }
    }

    // Write output file
    private void writeParserOutput(
            Map<String, Integer> termDictionary,
            Map<String, Integer> documentDictionary,
            Path outputPath) throws IOException {

        Path parentDir = outputPath.getParent();

        if (parentDir != null)
            Files.createDirectories(parentDir);

        List<Map.Entry<String, Integer>> termEntries =
                new ArrayList<>(termDictionary.entrySet());

        termEntries.sort(Map.Entry.comparingByKey());

        List<Map.Entry<String, Integer>> documentEntries =
                new ArrayList<>(documentDictionary.entrySet());

        System.out.println("Term Dictionary size: " + termDictionary.size());
        System.out.println("Document Dictionary size: " + documentDictionary.size());
        documentEntries.sort(
                Comparator.<Map.Entry<String, Integer>, Integer>comparing(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey));

        try (BufferedWriter writer =
                     Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            writer.write("Token   ID \n");
            // Term dictionary
            for (Map.Entry<String, Integer> entry : termEntries) {

                String term = sanitizeField(entry.getKey());
                Integer id = entry.getValue();

                if (!term.isEmpty() && id != null) {

                    writer.write(term);
                    writer.write('\t');
                    writer.write(Integer.toString(id));
                    writer.newLine();
                }
            }

            writer.newLine();
            writer.write("Document    ID\n");

            // Document dictionary
            for (Map.Entry<String, Integer> entry : documentEntries) {

                String documentName = sanitizeField(entry.getKey());
                Integer id = entry.getValue();

                if (documentName.isEmpty() || id == null || id <= 0)
                    continue;

                writer.write(documentName);
                writer.write('\t');
                writer.write(Integer.toString(id));
                writer.newLine();
            }
        }
    }

    // Clean fields
    private static String sanitizeField(String value) {

        if (value == null)
            return "";

        String cleaned =
                value.replace("\r", "").replace("\n", "").trim();

        if (!cleaned.isEmpty() && cleaned.charAt(0) == '>') {
            cleaned = cleaned.substring(1).trim();
        }
        return cleaned;
    }
}