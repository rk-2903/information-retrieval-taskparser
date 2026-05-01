/**
 * CSCE 5200 Information Retrieval — Project Phase 3
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * Query Processor: Vector Space Model with TF-IDF weighting and Cosine
 * Similarity scoring. Reads the indexes produced by Phase 2 (Indexer.java),
 * processes all queries in topics.txt under THREE settings (professor
 * requirement), writes one clearly-labelled output file per setting into
 * output_project3/, and evaluates Precision & Recall against main.qrels.
 *
 * ── Three retrieval settings ──────────────────────────────────────────────────
 *   Setting 1 — Title only           → output_project3/vsm_output_title.txt
 *   Setting 2 — Title + Description  → output_project3/vsm_output_title_desc.txt
 *   Setting 3 — Title + Narrative    → output_project3/vsm_output_title_narr.txt
 *
 *   Each file starts with a plain-text header identifying the setting, then:
 *     TOPIC <TAB> DOCNO <TAB> RANK <TAB> COSINE_SCORE   (one row per document)
 *   Documents are sorted by cosine score descending; rank resets per topic.
 *
 * ── Input files expected ──────────────────────────────────────────────────────
 *   output_phase2/term_dict.txt        stemmedTerm <TAB> termID
 *   output_phase2/doc_dict.txt         DOCNO <TAB> docID
 *   output_phase2/inverted_index.txt   termID: docID:freq; ...
 *   topics.txt                         TREC-format query file
 *   main.qrels                         Relevance judgments
 *
 * ── Compile & run ─────────────────────────────────────────────────────────────
 *   javac Porter.java QueryProcessor.java
 *   java  QueryProcessor [indexDir] [topicsFile] [qrelsFile] [outputDir]
 *
 *   Defaults (if no args):
 *     indexDir   = output_phase2
 *     topicsFile = topics.txt
 *     qrelsFile  = main.qrels
 *     outputDir  = output_project3
 * ══════════════════════════════════════════════════════════════════════════════
 */

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class QueryProcessor {

    // ── Configurable paths ─────────────────────────────────────────────────────
    private static final String DEFAULT_INDEX_DIR   = "output_phase2";
    private static final String DEFAULT_TOPICS_FILE = "topics.txt";
    private static final String DEFAULT_QRELS_FILE  = "main.qrels";
    // Output folder — one file per setting is written here (professor requirement)
    private static final String DEFAULT_OUTPUT_DIR  = "output_project3";

    // ── Query expansion settings ───────────────────────────────────────────────
    /** Three retrieval settings required by the project spec. */
    enum QuerySetting {
        TITLE_ONLY        ("Title only"),
        TITLE_AND_DESC    ("Title + Description"),
        TITLE_AND_NARR    ("Title + Narrative");

        final String label;
        QuerySetting(String label) { this.label = label; }
    }

    // ── Dictionaries loaded from Phase 2 output ────────────────────────────────

    /** stemmedTerm → termID */
    private final Map<String, Integer> termToId     = new HashMap<>();

    /** termID → stemmedTerm */
    private final Map<Integer, String> idToTerm     = new HashMap<>();

    /** DOCNO string → numeric docID */
    private final Map<String, Integer> docNameToId  = new HashMap<>();

    /** numeric docID → DOCNO string */
    private final Map<Integer, String> idToDocName  = new HashMap<>();

    // ── Inverted index loaded from disk ───────────────────────────────────────
    /**
     * termID → list of (docID, rawTermFrequency) pairs.
     * Using a list of int[] {docID, tf} keeps memory compact.
     */
    private final Map<Integer, List<int[]>> invertedIndex = new HashMap<>();

    /** Total number of documents in the collection (N). */
    private int totalDocCount = 0;

    /** Per-document L2 norm of TF-IDF vector (pre-computed for efficiency). */
    private final Map<Integer, Double> docTfIdfNorm = new HashMap<>();

    // ── Porter stemmer ─────────────────────────────────────────────────────────
    private final Porter stemmer = new Porter();

    // ── Regex patterns for topics.txt parsing ─────────────────────────────────
    private static final Pattern TOPIC_BLOCK   = Pattern.compile(
            "(?s)<top>(.*?)</top>", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUM_TAG       = Pattern.compile(
            "<num>\\s*Number:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_TAG     = Pattern.compile(
            "<title>([^<]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DESC_TAG      = Pattern.compile(
            "<desc>\\s*Description:\\s*([^<]+)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern NARR_TAG      = Pattern.compile(
            "<narr>\\s*Narrative:\\s*([^<]+)", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // ── Tokenisation (same pipeline as Phase 2 Indexer) ───────────────────────
    private static final Pattern NON_ALPHA  = Pattern.compile("[^a-z0-9]+");
    private static final Pattern HAS_DIGIT  = Pattern.compile(".*\\d.*");

    // ═══════════════════════════════════════════════════════════════════════════
    // Entry point
    // ═══════════════════════════════════════════════════════════════════════════
    public static void main(String[] args) throws IOException {
        String indexDir   = args.length > 0 ? args[0] : DEFAULT_INDEX_DIR;
        String topicsFile = args.length > 1 ? args[1] : DEFAULT_TOPICS_FILE;
        String qrelsFile  = args.length > 2 ? args[2] : DEFAULT_QRELS_FILE;
        // Per professor's instruction: all output goes inside output_project3/
        String outputDir  = args.length > 3 ? args[3] : DEFAULT_OUTPUT_DIR;

        Files.createDirectories(Paths.get(outputDir));

        QueryProcessor qp = new QueryProcessor();

        // Step 1 — Load Phase 2 dictionaries and inverted index
        qp.loadDictionaries(indexDir);
        qp.loadInvertedIndex(indexDir);
        qp.precomputeDocumentNorms();

        // Step 2 — Parse topics.txt
        List<Topic> topics = qp.parseTopics(topicsFile);
        System.out.printf("Loaded %d topics from %s%n", topics.size(), topicsFile);

        // Step 3 — Load relevance judgments (qrels)
        Map<Integer, Set<String>> relevantDocs = loadQrels(qrelsFile);

        // Step 4 — Run retrieval under ALL THREE settings.
        // Professor requirement: each setting gets its own clearly-labelled file
        // inside output_project3/ with a header identifying the setting.
        System.out.println("\n═══════════════════════════════════════════════════════");
        System.out.println("  VSM Retrieval — TF-IDF / Cosine Similarity");
        System.out.println("  3 settings: Title | Title+Desc | Title+Narr");
        System.out.println("═══════════════════════════════════════════════════════");

        // Each QuerySetting maps to a dedicated output filename
        Map<QuerySetting, String> settingToFile = new LinkedHashMap<>();
        settingToFile.put(QuerySetting.TITLE_ONLY,     "vsm_output_title.txt");
        settingToFile.put(QuerySetting.TITLE_AND_DESC, "vsm_output_title_desc.txt");
        settingToFile.put(QuerySetting.TITLE_AND_NARR, "vsm_output_title_narr.txt");

        for (Map.Entry<QuerySetting, String> entry : settingToFile.entrySet()) {
            QuerySetting setting  = entry.getKey();
            Path         filePath = Paths.get(outputDir, entry.getValue());

            System.out.printf("%n── Setting: %-30s → %s%n", setting.label, filePath);

            List<String> outputLines = new ArrayList<>();

            // ── Clear header inside every output file ────────────────────────
            // Professor: "clearly list out the settings before the output for
            // that specific setting" — done here via plain-text comment lines.
            outputLines.add("# =============================================================");
            outputLines.add("# CSCE 5200 IR — Project Phase 3: VSM Query Processor");
            outputLines.add("# Setting  : " + setting.label);
            outputLines.add("# Weighting: TF-IDF  (raw TF  x  log10(N / df))");
            outputLines.add("# Scoring  : Cosine Similarity");
            outputLines.add("# Columns  : TOPIC <TAB> DOCNO <TAB> RANK <TAB> COSINE_SCORE");
            outputLines.add("# =============================================================");
            outputLines.add("");

            for (Topic topic : topics) {
                String queryText = qp.buildQueryText(topic, setting);
                List<ScoredDocument> ranked = qp.retrieveAndRank(queryText);

                // Rank counter resets to 1 for each topic (per spec)
                int rank = 1;
                for (ScoredDocument sd : ranked) {
                    outputLines.add(String.format(" %d\t%s\t%d\t%f",
                            topic.number, sd.docName, rank++, sd.cosineScore));
                }

                // Evaluate Precision & Recall; results printed to console
                Set<String> relevant = relevantDocs.getOrDefault(
                        topic.number, Collections.emptySet());
                qp.evaluatePrecisionRecall(topic.number, ranked, relevant, setting);
            }

            Files.write(filePath, outputLines, StandardCharsets.UTF_8);
            System.out.printf("  Written → %s%n", filePath);
        }

        System.out.printf("%nAll 3 output files saved in: %s/%n", outputDir);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Step 1a — Load term and document dictionaries
    // ═══════════════════════════════════════════════════════════════════════════
    /**
     * Reads term_dict.txt and doc_dict.txt produced by Phase 2 Indexer.
     * Format: key <TAB> id
     */
    private void loadDictionaries(String indexDir) throws IOException {
        // term_dict.txt
        Path termDictPath = Paths.get(indexDir, "term_dict.txt");
        try (BufferedReader reader =
                     Files.newBufferedReader(termDictPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t", 2);
                if (parts.length < 2) continue;
                String term = parts[0].trim();
                int    id   = Integer.parseInt(parts[1].trim());
                termToId.put(term, id);
                idToTerm.put(id, term);
            }
        }

        // doc_dict.txt
        Path docDictPath = Paths.get(indexDir, "doc_dict.txt");
        try (BufferedReader reader =
                     Files.newBufferedReader(docDictPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t", 2);
                if (parts.length < 2) continue;
                String docName = parts[0].trim();
                int    docId   = Integer.parseInt(parts[1].trim());
                docNameToId.put(docName, docId);
                idToDocName.put(docId, docName);
            }
        }

        totalDocCount = docNameToId.size();
        System.out.printf("Loaded %,d terms and %,d documents from dictionaries.%n",
                termToId.size(), totalDocCount);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Step 1b — Load inverted index from disk
    // ═══════════════════════════════════════════════════════════════════════════
    /**
     * Reads inverted_index.txt. Format per line:
     *   termID: docID:freq; docID:freq; ...
     */
    private void loadInvertedIndex(String indexDir) throws IOException {
        Path invertedPath = Paths.get(indexDir, "inverted_index.txt");
        try (BufferedReader reader =
                     Files.newBufferedReader(invertedPath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // Split at the first colon to get termID and posting list string
                int colonPos = line.indexOf(':');
                if (colonPos < 0) continue;

                int termId;
                try {
                    termId = Integer.parseInt(line.substring(0, colonPos).trim());
                } catch (NumberFormatException e) { continue; }

                String postingListStr = line.substring(colonPos + 1).trim();
                List<int[]> postings  = new ArrayList<>();

                for (String token : postingListStr.split(";")) {
                    token = token.trim();
                    if (token.isEmpty()) continue;
                    String[] pair = token.split(":");
                    if (pair.length < 2) continue;
                    try {
                        int docId = Integer.parseInt(pair[0].trim());
                        int freq  = Integer.parseInt(pair[1].trim());
                        postings.add(new int[]{docId, freq});
                    } catch (NumberFormatException e) { /* skip */ }
                }

                if (!postings.isEmpty()) {
                    invertedIndex.put(termId, postings);
                }
            }
        }
        System.out.printf("Loaded inverted index: %,d term entries.%n",
                invertedIndex.size());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Step 1c — Pre-compute L2 norm of each document's TF-IDF vector
    // ═══════════════════════════════════════════════════════════════════════════
    /**
     * For cosine similarity we need ||d|| = sqrt(sum of (tf*idf)^2) per document.
     * Pre-computing this once avoids recomputing for every query.
     *
     * TF-IDF scheme used (standard):
     *   tf_weight(t,d)  = tf(t,d)              (raw term frequency)
     *   idf(t)          = log10(N / df(t))     (base-10 log)
     *   weight(t,d)     = tf_weight * idf
     */
    private void precomputeDocumentNorms() {
        int N = totalDocCount;

        // Accumulate sum-of-squares per document
        Map<Integer, Double> sumOfSquares = new HashMap<>();

        for (Map.Entry<Integer, List<int[]>> entry : invertedIndex.entrySet()) {
            int termId       = entry.getKey();
            List<int[]> postingList = entry.getValue();

            int df = postingList.size();                       // document frequency
            double idf = Math.log10((double) N / df);         // IDF

            for (int[] posting : postingList) {
                int    docId    = posting[0];
                int    rawTf    = posting[1];
                double tfidf    = rawTf * idf;                // TF-IDF weight
                sumOfSquares.merge(docId, tfidf * tfidf, Double::sum);
            }
        }

        // Take square root to get L2 norm
        for (Map.Entry<Integer, Double> e : sumOfSquares.entrySet()) {
            docTfIdfNorm.put(e.getKey(), Math.sqrt(e.getValue()));
        }

        System.out.printf("Pre-computed TF-IDF norms for %,d documents.%n",
                docTfIdfNorm.size());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Step 2 — Parse topics.txt into Topic objects
    // ═══════════════════════════════════════════════════════════════════════════
    private List<Topic> parseTopics(String topicsFilePath) throws IOException {
        String fileContent = Files.readString(
                Paths.get(topicsFilePath), StandardCharsets.UTF_8);

        List<Topic> topics = new ArrayList<>();
        Matcher topicMatcher = TOPIC_BLOCK.matcher(fileContent);

        while (topicMatcher.find()) {
            String block = topicMatcher.group(1);
            Topic topic  = new Topic();

            Matcher m;

            m = NUM_TAG.matcher(block);
            if (m.find()) topic.number = Integer.parseInt(m.group(1).trim());

            m = TITLE_TAG.matcher(block);
            if (m.find()) topic.title = m.group(1).trim();

            m = DESC_TAG.matcher(block);
            if (m.find()) topic.description = m.group(1).trim();

            m = NARR_TAG.matcher(block);
            if (m.find()) topic.narrative = m.group(1).trim();

            if (topic.number > 0) topics.add(topic);
        }
        return topics;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Step 3 — Build query text according to the active setting
    // ═══════════════════════════════════════════════════════════════════════════
    private String buildQueryText(Topic topic, QuerySetting setting) {
        switch (setting) {
            case TITLE_AND_DESC:
                return topic.title + " " + topic.description;
            case TITLE_AND_NARR:
                return topic.title + " " + topic.narrative;
            default: // TITLE_ONLY
                return topic.title;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Step 4 — Retrieve and rank documents for a single query text
    // ═══════════════════════════════════════════════════════════════════════════
    /**
     * Implements the accumulator-based VSM scoring algorithm (Figure 6.14).
     *
     * For each query term q_i:
     *   1. Compute query TF-IDF weight: qtfidf = tf(q_i, query) * idf(q_i)
     *   2. For each document d in the posting list of q_i:
     *        score[d] += qtfidf * tfidf(q_i, d)
     *   3. After all query terms: normalise score[d] by ||d|| * ||q||
     *
     * Returns documents sorted by cosine score descending.
     */
    private List<ScoredDocument> retrieveAndRank(String rawQueryText) {
        int N = totalDocCount;

        // ── Tokenise and stem the query ──────────────────────────────────────
        // queryTermFreq: stemmedTerm → frequency in query
        Map<String, Integer> queryTermFreq = new HashMap<>();
        for (String rawToken : NON_ALPHA.split(rawQueryText.toLowerCase(Locale.ROOT))) {
            if (rawToken.isEmpty()) continue;
            if (HAS_DIGIT.matcher(rawToken).matches()) continue;
            String stemmed = stem(rawToken);
            if (stemmed.isEmpty()) continue;
            queryTermFreq.merge(stemmed, 1, Integer::sum);
        }

        // ── Accumulate document scores ───────────────────────────────────────
        // accumulatorScores: docID → running dot-product sum
        Map<Integer, Double> accumulatorScores = new HashMap<>();
        double queryNormSquared = 0.0;

        for (Map.Entry<String, Integer> qTermEntry : queryTermFreq.entrySet()) {
            String queryTerm = qTermEntry.getKey();
            int    queryTf   = qTermEntry.getValue();

            Integer termId = termToId.get(queryTerm);
            if (termId == null) continue; // term not in collection vocabulary

            List<int[]> postingList = invertedIndex.get(termId);
            if (postingList == null || postingList.isEmpty()) continue;

            int    df      = postingList.size();
            double idf     = Math.log10((double) N / df);
            double qTfIdf  = queryTf * idf;   // query term TF-IDF weight

            queryNormSquared += qTfIdf * qTfIdf;

            // Add contribution to each document in the posting list
            for (int[] posting : postingList) {
                int    docId      = posting[0];
                double docTfIdf   = posting[1] * idf;  // doc term TF-IDF weight
                accumulatorScores.merge(docId, qTfIdf * docTfIdf, Double::sum);
            }
        }

        if (accumulatorScores.isEmpty()) return Collections.emptyList();

        double queryNorm = Math.sqrt(queryNormSquared);

        // ── Normalise scores to get cosine similarity ─────────────────────────
        List<ScoredDocument> results = new ArrayList<>(accumulatorScores.size());
        for (Map.Entry<Integer, Double> scoreEntry : accumulatorScores.entrySet()) {
            int    docId    = scoreEntry.getKey();
            double dotProd  = scoreEntry.getValue();
            double docNorm  = docTfIdfNorm.getOrDefault(docId, 1.0);
            double cosine   = (queryNorm > 0 && docNorm > 0)
                    ? dotProd / (queryNorm * docNorm)
                    : 0.0;

            if (cosine > 0) {
                String docName = idToDocName.get(docId);
                if (docName != null) results.add(new ScoredDocument(docName, cosine));
            }
        }

        // Sort by cosine score descending; break ties by docName for stability
        results.sort((a, b) -> {
            int cmp = Double.compare(b.cosineScore, a.cosineScore);
            return cmp != 0 ? cmp : a.docName.compareTo(b.docName);
        });

        return results;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Step 5 — Evaluate Precision and Recall
    // ═══════════════════════════════════════════════════════════════════════════
    /**
     * Computes Precision@k and Recall@k at multiple cut-off points,
     * and overall precision and recall across all retrieved documents.
     *
     * Printed to console for inclusion in the project report.
     */
    private void evaluatePrecisionRecall(int topicNum,
                                         List<ScoredDocument> ranked,
                                         Set<String> relevantDocNames,
                                         QuerySetting setting) {
        int totalRelevant = relevantDocNames.size();
        if (totalRelevant == 0) {
            System.out.printf("  Topic %d: No relevance judgments found.%n", topicNum);
            return;
        }

        // Evaluate at rank cut-offs: 10, 20, 50, 100, all
        int[] cutoffs = {10, 20, 50, 100, ranked.size()};

        System.out.printf("  Topic %d | %s | Total relevant: %d | Retrieved: %d%n",
                topicNum, setting.label, totalRelevant, ranked.size());
        System.out.printf("  %-8s  %-12s  %-12s%n", "Cut-off", "Precision", "Recall");
        System.out.printf("  %-8s  %-12s  %-12s%n", "-------", "---------", "------");

        int retrievedRelevant = 0;
        int lastCutoffIndex   = 0;

        for (int cutoff : cutoffs) {
            int limit = Math.min(cutoff, ranked.size());
            // Count relevant docs in ranked[lastCutoffIndex..limit)
            for (int i = lastCutoffIndex; i < limit; i++) {
                if (relevantDocNames.contains(ranked.get(i).docName)) {
                    retrievedRelevant++;
                }
            }
            lastCutoffIndex = limit;

            double precision = limit > 0 ? (double) retrievedRelevant / limit : 0.0;
            double recall    = totalRelevant > 0
                    ? (double) retrievedRelevant / totalRelevant : 0.0;

            String cutoffLabel = (cutoff == ranked.size() && cutoff != 10
                    && cutoff != 20 && cutoff != 50 && cutoff != 100)
                    ? "All(" + cutoff + ")" : String.valueOf(cutoff);

            System.out.printf("  %-8s  %-12.4f  %-12.4f%n",
                    cutoffLabel, precision, recall);
        }
        System.out.println();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Load qrels file
    // ═══════════════════════════════════════════════════════════════════════════
    /**
     * Parses main.qrels. Format: TOPIC  ITERATION  DOCNO  RELEVANCY
     * Builds a map: topicID → set of relevant DOCNO strings (relevancy == 1).
     */
    private static Map<Integer, Set<String>> loadQrels(String qrelsPath) throws IOException {
        Map<Integer, Set<String>> relevanceMap = new HashMap<>();
        try (BufferedReader reader =
                     Files.newBufferedReader(Paths.get(qrelsPath), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 4) continue;
                int    topicNum  = Integer.parseInt(parts[0]);
                String docName   = parts[2];
                int    relevancy = Integer.parseInt(parts[3]);
                if (relevancy == 1) {
                    relevanceMap.computeIfAbsent(topicNum, k -> new HashSet<>())
                            .add(docName);
                }
            }
        }
        System.out.printf("Loaded qrels for %d topics.%n", relevanceMap.size());
        return relevanceMap;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Utility helpers
    // ═══════════════════════════════════════════════════════════════════════════

    /** Applies Porter stemming; returns original token on error. */
    private String stem(String token) {
        try { return stemmer.stripAffixes(token); }
        catch (Throwable t) { return token; }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Inner data classes
    // ═══════════════════════════════════════════════════════════════════════════

    /** Holds all parsed fields of a single TREC topic / query. */
    static class Topic {
        int    number;
        String title       = "";
        String description = "";
        String narrative   = "";
    }

    /** A document and its cosine similarity score for one query. */
    static class ScoredDocument {
        final String docName;
        final double cosineScore;

        ScoredDocument(String docName, double cosineScore) {
            this.docName     = docName;
            this.cosineScore = cosineScore;
        }
    }
}