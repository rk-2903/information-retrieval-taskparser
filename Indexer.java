import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class Indexer {

    // Dictionaries built while scanning the corpus
    private final Map<String, Integer> termToId  = new LinkedHashMap<>();
    private final Map<String, Integer> docNameToId = new LinkedHashMap<>();
    private final List<String> docIdToName = new ArrayList<>();
    private final List<String> termIdToStem = new ArrayList<>();
    private final Map<Integer, Map<Integer, Integer>> forwardIndex  = new LinkedHashMap<>();
    private final Map<Integer, Map<Integer, Integer>> invertedIndex = new LinkedHashMap<>();
    private final Set<String> stopWords = new HashSet<>();
    private final Porter stemmer = new Porter();

    // TREC regex patterns

    private static final Pattern TREC_DOC_BLOCK = Pattern.compile("<DOC>(.*?)</DOC>", Pattern.DOTALL);

    private static final Pattern TREC_DOCNO_TAG = Pattern.compile(
            "<DOCNO>\\s*(.*?)\\s*</DOCNO>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);


    public static void main(String[] args) throws Exception {

        // Put your path from command-line. I added my path as fallback
        String corpusDir = (args.length > 0) ? args[0] : "ft911";
        String stopWordFile = (args.length > 1) ? args[1] : "stopwordlist.txt";
        String outputDir    = (args.length > 2) ? args[2] : "output_phase2";

        long startTimeMs = System.currentTimeMillis();

        Indexer indexer = new Indexer();

        // Load stop words
        indexer.loadStopWords(stopWordFile);

        // Parse corpus and build forward + inverted indexes
        indexer.buildIndexesFromCorpus(Paths.get(corpusDir));

        // Write all output files
        Files.createDirectories(Paths.get(outputDir));
        indexer.writeDictionaries(outputDir);
        indexer.writeForwardIndex(outputDir  + File.separator + "forward_index.txt");
        indexer.writeInvertedIndex(outputDir + File.separator + "inverted_index.txt");

        // Statistics
        long elapsedMs = System.currentTimeMillis() - startTimeMs;
        long forwardIndexBytes  = Files.size(Paths.get(outputDir, "forward_index.txt"));
        long invertedIndexBytes = Files.size(Paths.get(outputDir, "inverted_index.txt"));

        System.out.println("Indexing time (ms)        : " + elapsedMs);
        System.out.println("forward_index.txt  (bytes): " + forwardIndexBytes);
        System.out.println("inverted_index.txt (bytes): " + invertedIndexBytes);
        System.out.println("Documents indexed         : " + indexer.docNameToId.size());
        System.out.println("Unique terms              : " + indexer.termToId.size());

        // Interactive query mode
        indexer.runInteractiveQueryMode();
    }

    private void loadStopWords(String stopWordFilePath) throws IOException {
        Path path = Paths.get(stopWordFilePath);
        if (!Files.exists(path)) {
            System.out.println("[WARNING] Stop-word file not found: " + stopWordFilePath);
            return;
        }
        try (BufferedReader reader =
                     Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String word = line.trim().toLowerCase();
                if (!word.isEmpty()) stopWords.add(word);
            }
        }
        System.out.println("Stop words loaded: " + stopWords.size());
    }

    private void buildIndexesFromCorpus(Path corpusDir) throws IOException {

        // Walk all corpus files and build the forward index
        Files.walk(corpusDir)
                .filter(Files::isRegularFile)
                .forEach(corpusFile -> {
                    try {
                        String fileContent = Files.readString(corpusFile, StandardCharsets.UTF_8);
                        Matcher docBlockMatcher = TREC_DOC_BLOCK.matcher(fileContent);

                        while (docBlockMatcher.find()) {
                            // Everything between <DOC> and </DOC>
                            String docBlock = docBlockMatcher.group(1);

                            // Extract TREC document identifier
                            String trecDocName = extractTrecDocName(docBlock);
                            if (trecDocName == null || trecDocName.isEmpty()) continue;

                            // Assign a numeric docID (creates one if first time seen)
                            int docId = assignDocId(trecDocName);

                            // Strip all XML/SGML markup tags — keeps only plain text.
                            // This is broader than extracting only <TEXT> and correctly
                            // captures content in <HEADLINE>, <DATELINE>, etc.
                            String plainText = stripAllMarkupTags(docBlock);

                            // Tokenise on any non-alphabetic character (lowercase first)
                            String[] rawTokens = plainText.toLowerCase().split("[^a-z0-9]+");

                            // termFrequencyInDoc: termID → occurrence count for this doc
                            Map<Integer, Integer> termFrequencyInDoc =
                                    forwardIndex.computeIfAbsent(docId, k -> new HashMap<>());

                            for (String rawToken : rawTokens) {
                                if (rawToken.isEmpty()) continue;

                                // Drop any token that contains a digit
                                if (containsDigit(rawToken)) continue;

                                // Drop stop words
                                if (stopWords.contains(rawToken)) continue;

                                // Apply Porter stemming
                                String stemmedToken = stemmer.stripAffixes(rawToken);
                                if (stemmedToken == null || stemmedToken.isEmpty()) continue;

                                // Assign a numeric termID (creates one if first time seen)
                                int termId = assignTermId(stemmedToken);

                                // Increment term frequency for this document
                                termFrequencyInDoc.put(
                                        termId,
                                        termFrequencyInDoc.getOrDefault(termId, 0) + 1);
                            }
                        }

                    } catch (IOException ioEx) {
                        throw new UncheckedIOException(ioEx);
                    }
                });

        // Derive inverted index from forward index
        // For every (docID, termID, freq) triple in the forward index,
        // add a posting (docID → freq) to the inverted index under termID.
        for (Map.Entry<Integer, Map<Integer, Integer>> docEntry : forwardIndex.entrySet()) {
            int docId = docEntry.getKey();
            for (Map.Entry<Integer, Integer> termEntry : docEntry.getValue().entrySet()) {
                int termId = termEntry.getKey();
                int freq   = termEntry.getValue();
                invertedIndex
                        .computeIfAbsent(termId, k -> new HashMap<>())
                        .put(docId, freq);
            }
        }
    }

    /** Extracts the TREC document identifier from a raw doc block string. */
    private String extractTrecDocName(String docBlock) {
        Matcher m = TREC_DOCNO_TAG.matcher(docBlock);
        return m.find() ? m.group(1).trim() : null;
    }

//    Removing all SGML/HTML markup tags from text, leaving only plain content.
    private static String stripAllMarkupTags(String rawText) {
        return rawText.replaceAll("<[^>]+>", " ");
    }

    /** Returns true if the token contains at least one ASCII digit character. */
    private static boolean containsDigit(String token) {
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c >= '0' && c <= '9') return true;
        }
        return false;
    }

    /**
     * Returns the existing termID for a stemmed term, or assigns a new 1-based
     * ID if the term has not been seen before.
     */
    private int assignTermId(String stemmedTerm) {
        Integer existingId = termToId.get(stemmedTerm);
        if (existingId != null) return existingId;
        int newId = termToId.size() + 1;   // 1-based
        termToId.put(stemmedTerm, newId);
        termIdToStem.add(stemmedTerm);      // index 0 = termID 1
        return newId;
    }

    /**
     * Returns the existing docID for a TREC DOCNO string, or assigns a new
     * 1-based ID if this document has not been seen before.
     */
    private int assignDocId(String trecDocName) {
        Integer existingId = docNameToId.get(trecDocName);
        if (existingId != null) return existingId;
        int newId = docNameToId.size() + 1; // 1-based
        docNameToId.put(trecDocName, newId);
        docIdToName.add(trecDocName);        // index 0 = docID 1
        return newId;
    }

    private void writeDictionaries(String outputDir) throws IOException {

        // term_dict.txt
        try (BufferedWriter writer = Files.newBufferedWriter(
                Paths.get(outputDir, "term_dict.txt"), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, Integer> entry : termToId.entrySet()) {
                writer.write(entry.getKey() + "\t" + entry.getValue());
                writer.newLine();
            }
        }

        // doc_dict.txt
        try (BufferedWriter writer = Files.newBufferedWriter(
                Paths.get(outputDir, "doc_dict.txt"), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, Integer> entry : docNameToId.entrySet()) {
                writer.write(entry.getKey() + "\t" + entry.getValue());
                writer.newLine();
            }
        }

        System.out.println("Dictionaries written → " + outputDir
                + File.separator + "term_dict.txt  /  doc_dict.txt");
    }


    private void writeForwardIndex(String outputFilePath) throws IOException {
        try (BufferedWriter writer =
                     Files.newBufferedWriter(Paths.get(outputFilePath), StandardCharsets.UTF_8)) {

            // Sort docIDs in ascending order for deterministic, readable output
            List<Integer> sortedDocIds = new ArrayList<>(forwardIndex.keySet());
            Collections.sort(sortedDocIds);

            for (int docId : sortedDocIds) {
                Map<Integer, Integer> termFrequencies = forwardIndex.get(docId);

                // Sort termIDs within this document entry
                List<Integer> sortedTermIds = new ArrayList<>(termFrequencies.keySet());
                Collections.sort(sortedTermIds);

                StringBuilder line = new StringBuilder();
                line.append(docId).append(": ");

                for (int i = 0; i < sortedTermIds.size(); i++) {
                    int termId = sortedTermIds.get(i);
                    line.append(termId).append(":").append(termFrequencies.get(termId));
                    if (i < sortedTermIds.size() - 1) line.append("; ");
                }

                writer.write(line.toString());
                writer.newLine();
            }
        }
        System.out.println("Forward  index written → " + outputFilePath);
    }


    private void writeInvertedIndex(String outputFilePath) throws IOException {
        try (BufferedWriter writer =
                     Files.newBufferedWriter(Paths.get(outputFilePath), StandardCharsets.UTF_8)) {

            // Sort termIDs in ascending order
            List<Integer> sortedTermIds = new ArrayList<>(invertedIndex.keySet());
            Collections.sort(sortedTermIds);

            for (int termId : sortedTermIds) {
                Map<Integer, Integer> postingList = invertedIndex.get(termId);

                // Sort docIDs in ascending order within this posting list
                List<Integer> sortedDocIds = new ArrayList<>(postingList.keySet());
                Collections.sort(sortedDocIds);

                StringBuilder line = new StringBuilder();
                line.append(termId).append(": ");

                for (int i = 0; i < sortedDocIds.size(); i++) {
                    int docId = sortedDocIds.get(i);
                    line.append(docId).append(":").append(postingList.get(docId));
                    if (i < sortedDocIds.size() - 1) line.append("; ");
                }

                writer.write(line.toString());
                writer.newLine();
            }
        }
        System.out.println("Inverted index written → " + outputFilePath);
    }


    private void runInteractiveQueryMode() throws IOException {
        System.out.println("\nQuery mode — type ':q' to exit");
        System.out.println("Enter a term to see its inverted-index postings (DOCNO / docID : freq):");

        try (BufferedReader console =
                     new BufferedReader(new InputStreamReader(System.in))) {

            while (true) {
                System.out.print("\nEnter any term: ");
                String userInput = console.readLine();
                if (userInput == null) break;

                String queryRaw = userInput.trim();
                if (queryRaw.equals(":q") || queryRaw.isEmpty()) break;

                // Apply the same normalisation pipeline as indexing
                String queryLower = queryRaw.toLowerCase();

                if (containsDigit(queryLower)) {
                    System.out.println("  Dropped — query contains a digit.");
                    continue;
                }
                if (stopWords.contains(queryLower)) {
                    System.out.println("  Dropped — '" + queryRaw + "' is a stop word.");
                    continue;
                }

                String stemmedQuery = stemmer.stripAffixes(queryLower);

                // Dictionary lookup
                Integer termId = termToId.get(stemmedQuery);
                if (termId == null) {
                    System.out.println("  Term '" + queryRaw
                            + "' (stemmed: '" + stemmedQuery + "') not found in dictionary.");
                    continue;
                }

                // Inverted index lookup
                Map<Integer, Integer> postingList =
                        invertedIndex.getOrDefault(termId, Collections.emptyMap());

                if (postingList.isEmpty()) {
                    System.out.println("  No postings for term '" + stemmedQuery + "'.");
                    continue;
                }

                // Print results
                System.out.println("  Term='" + queryRaw + "'  stemmed='"
                        + stemmedQuery + "'  termID=" + termId
                        + "  appears in " + postingList.size() + " doc(s):");

                List<Integer> sortedDocIds = new ArrayList<>(postingList.keySet());
                Collections.sort(sortedDocIds);
                System.out.println("\n");
                System.out.print(stemmedQuery + "("+termId+ "): " );
                for (int docId : sortedDocIds) {
                    System.out.print(docId +":" + postingList.get(docId) + "; ");
                }
                System.out.println("\n");
            }

        }
    }
}