import java.io.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.regex.*;


public class TextParser {

    //Paths below are all relative to given project folder
    private static final Path STOPWORDS = Paths.get("stopwordlist.txt");
    private static final Path FT_DIR = Paths.get("ft911");
    private static final Path OUTPUT_PATH = Paths.get("parser_output.txt");
    private static final String INPUT_GLOB = "ft911_*";

    //Data
    private final Set<String> stopWords = new HashSet<>();
    private final Set<String> uniqueTerms = new TreeSet<>();
    private final Map<String, Integer> documentDictionary = new TreeMap<>();

    //Porter Stemmer provided
    private final Porter stemmer = new Porter();

    //Regex Patterns:

    //Single document block
    private static final Pattern documentPattern   = Pattern.compile("(?s)<DOC>.*?<\\/DOC>");
    //DOCNO
    private static final Pattern documentNoPattern = Pattern.compile("<DOCNO\\s*([^<\\s]+)\\s*<\\/DOCNO>", Pattern.CASE_INSENSITIVE);
    //collect inner text
    private static final Pattern textPattern = Pattern.compile("(?is)<TEXT>(.*?)<\\/TEXT>");

    //Tokenization: Splitting on non-alphanumeric; skip tokens that contain any digit
    private static final Pattern splitPattern = Pattern.compile("[^A-Za-z0-9]+");
    private static final Pattern hasDigit = Pattern.compile(".*\\d.*");

    public static void main(String[] args) {
        try {
            System.out.println("Running TextParser");
            new TextParser().run();
            System.out.println("Finished TextParser");
        } catch(Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    //run method: load stopwords => scan files =>parse docs =>write dictionaries
    private void run() throws IOException {
        //Load the stopwords
        loadStopWordsList(STOPWORDS);

        //Iterate all Ft911_* files (relative path, no absolute paths)
        List<Path> inputs = listInputFiles(FT_DIR, INPUT_GLOB);
        if(inputs.isEmpty()){
            throw new FileNotFoundException("No input files matching '"+INPUT_GLOB+"'in folder 'ft911'.");
        }

        //Parse each file -> each <DOC>
        for (Path p : inputs){
            String content = Files.readString(p, StandardCharsets.UTF_8);
            Matcher mDoc = documentPattern.matcher(content);
            while(mDoc.find()){
                String docBlock = mDoc.group();
                String docNo = extractDocNo(docBlock);
                if(docNo == null) continue; //skip for malformed docs quietly

                //document dictionary rule: FT911-X ->ID x (numeric part)
                Integer docId = extractNumericId(docNo);
                if(docId==null) continue; //skip if docNo is invalid
                documentDictionary.put(docNo,docId);

                //Collect text from all <TEXT> blocks inside this document
                StringBuilder text = new StringBuilder();
                Matcher mText = textPattern.matcher(docBlock);
                while(mText.find()) {
                    text.append(mText.group(1)).append(' ');
                }

                //Tokenization + cleaning + stopword removal + stemming
                for(String raw : splitPattern.split(text.toString())){
                    if(raw.isEmpty()) continue;
                    String t= raw.toLowerCase(Locale.ROOT);
                    //Skip tokens that are numbers or contain any digit
                    if(hasDigit.matcher(t).matches()) continue;
                    if(stopWords.contains(t)) continue;

                    //Stem using Porter
                    String stem = stem(t);
                    if(!stem.isEmpty()){
                        uniqueTerms.add(stem);
                    }
                }
            }
        }

        //Assign incremental term IDs in Alphabetical order as per given instructions.
        Map<String,Integer> termDictionary = new LinkedHashMap<>();
        int termId =1;
        for (String term: uniqueTerms){
            termDictionary.put(term, termId++);
        }

        //Write parser_output.txt
        writeOutputDirectories(termDictionary, documentDictionary, OUTPUT_PATH);
    }

    //Helper methods implementation
    private void loadStopWordsList(Path stopPath) throws IOException {
        try(BufferedReader br = Files.newBufferedReader(stopPath, StandardCharsets.UTF_8)){
            String line;
            while((line=br.readLine())!= null){
                String w = line.trim().toLowerCase(Locale.ROOT);
                if(!w.isEmpty()) stopWords.add(w);
            }
        }
    }

    //Finds all input files under dir that match the glob
    private List<Path> listInputFiles(Path dir, String glob) throws IOException {
        List<Path> files = new ArrayList<>();
        try(DirectoryStream<Path> dstream = Files.newDirectoryStream(dir, glob)){
            for(Path p:dstream){
                if(Files.isRegularFile(p)) files.add(p);
            }
        }
        //Sort by filename to keep processing deterministic
        files.sort(Comparator.naturalOrder());
        return files;
    }

    //Extracts the <DOCNO>…</DOCNO> contents
    private String extractDocNo(String docBlock) {
        Matcher m = documentNoPattern.matcher(docBlock);
        return m.find() ? m.group(1).trim() : null;
    }

    //parses numeric tail after the final '-' in docNo (e.g., FT911-1234 -> 1234)
    private Integer extractNumericId(String docNo) {
        //to expect something like "FT911-1234" -> 1234
        int dash = docNo.lastIndexOf('-');
        if(dash<=0 || dash == docNo.length()-1)
            return null;
        String tail = docNo.substring(dash + 1);
        try {
            return Integer.parseInt(tail);
        } catch(NumberFormatException nfe){
            return null;
        }
    }

    private String stem(String token) {
        //Porter.java usually exposes stripAffixes(String) or similar
        try{
            return stemmer.stripAffixes(token);
        }catch(Throwable t){
            //fallback: return token as-is if any unexpected issue
            return token;
        }
    }

    private void writeOutputDirectories(Map<String,Integer> termDictionary, Map<String,Integer> documentDictionary, Path out) throws IOException{
        //create a parent directory if needed
        Path parent = out.getParent();
        if(parent !=null)
            Files.createDirectories(parent); //parent is null if writing in "."
        List<Map.Entry<String,Integer>> terms = new ArrayList<>(termDictionary.entrySet());
        terms.sort(Map.Entry.comparingByKey()); //alphabetical, just in case

        //Sorting docs by their numeric ID, then by name.
        List<Map.Entry<String,Integer>> docs = new ArrayList<>(documentDictionary.entrySet());
        docs.sort(Comparator.<Map.Entry<String,Integer>,Integer>comparing(Map.Entry::getValue)
                .thenComparing(Map.Entry::getKey));

        try(BufferedWriter bw = Files.newBufferedWriter(out, StandardCharsets.UTF_8)){
            //Terms
            for(Map.Entry<String,Integer> e:terms) {
                String term = safeField(e.getKey());
                Integer id = e.getValue();
                if(!term.isEmpty() && id != null){
                    bw.write(term);
                    bw.write('\t');
                    bw.write(Integer.toString(id));
                    bw.newLine();
                }
            }
            //blank separator
            bw.newLine();

            //Then documents (name then ID). doc Ids are already the numeric part.
            for(Map.Entry<String,Integer> e : docs){
                String docNo = safeField(e.getKey());
                Integer id = e.getValue();

                //Skip malformed entries
                if(docNo.isEmpty() || id==null || id<=0) continue;
                bw.write(docNo);
                bw.write('\t');
                bw.write(Integer.toString(id));
                bw.newLine();
            }
        }
    }

    //to remove any whitespace or stray characters.
    private static String safeField(String s) {
        if(s==null) return "";
        String cleaned=s.replace("\r","").replace("\n","").trim();
        if(!cleaned.isEmpty() && cleaned.charAt(0)=='>'){
            cleaned=cleaned.substring(1).trim();
        }
        return cleaned;
    }
}