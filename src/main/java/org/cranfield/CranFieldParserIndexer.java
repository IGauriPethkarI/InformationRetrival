package org.cranfield;

import org.apache.lucene.analysis.*;
import org.apache.lucene.analysis.core.SimpleAnalyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.en.PorterStemFilter;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.standard.StandardAnalyzer;

import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;

import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;

import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;

import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.TermQuery;

import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.apache.lucene.search.similarities.LMDirichletSimilarity;
import org.apache.lucene.search.similarities.LMJelinekMercerSimilarity;

import org.apache.lucene.index.Term;

import org.apache.lucene.store.FSDirectory;

import java.io.*;

import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.*;

public class CranFieldParserIndexer {

    private static final String ROOT_INDEX_PATH = "index/";
    private static final Path INTERACTIVE_INDEX_PATH =  Paths.get(ROOT_INDEX_PATH+"index");
    private static final String CRAN_FILE = "cran/cran.all.1400";

    private static final int DEFAULT_TOP_K = 100;

    public static class CranFieldDocument {
        public String id = "";
        public String title = "";
        public String author = "";
        public String biblio = "";
        public String body = "";
    }

    public static List<CranFieldDocument> parseCranField(File cranAll) throws IOException {
        List<CranFieldDocument> docs = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(cranAll))) {
            String line;
            CranFieldDocument cur = null;
            String section = null;
            StringBuilder sb = new StringBuilder();
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith(".I ")) {
                    if (cur != null) {
                        assignSection(cur, section, sb.toString().trim());
                        docs.add(cur);
                    }
                    cur = new CranFieldDocument();
                    cur.id = line.substring(3).trim();
                    section = null;
                    sb.setLength(0);
                } else if (line.equals(".T") || line.equals(".A") || line.equals(".B") || line.equals(".W")) {
                    if (cur != null && section != null) assignSection(cur, section, sb.toString().trim());
                    section = line.substring(1);
                    sb.setLength(0);
                } else if (section != null) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(line);
                }
            }
            if (cur != null) {
                assignSection(cur, section, sb.toString().trim());
                docs.add(cur);
            }
        }
        return docs;
    }

    private static void assignSection(CranFieldDocument doc, String section, String text) {
        if (section == null) return;
        switch (section) {
            case "T": doc.title = text; break;
            case "A": doc.author = text; break;
            case "B": doc.biblio = text; break;
            case "W": doc.body = text; break;
        }
    }

    public static void buildIndex(Path indexPath, List<CranFieldDocument> docs, Analyzer analyzer) throws Exception {
        var dir = FSDirectory.open(indexPath);
        IndexWriterConfig iwc = new IndexWriterConfig(analyzer);
        iwc.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
        try (IndexWriter writer = new IndexWriter(dir, iwc)) {
            for (CranFieldDocument cd : docs) {
                Document d = new Document();
                d.add(new StringField("id", cd.id == null ? "" : cd.id, Field.Store.YES));
                d.add(new TextField("title", cd.title == null ? "" : cd.title, Field.Store.YES));
                d.add(new TextField("author", cd.author == null ? "" : cd.author, Field.Store.YES));
                d.add(new TextField("biblio", cd.biblio == null ? "" : cd.biblio, Field.Store.NO));
                d.add(new TextField("body", cd.body == null ? "" : cd.body, Field.Store.YES));
                writer.addDocument(d);
            }
            writer.commit();
        }
    }

    public static Analyzer chooseAnalyzer(Scanner sc) {
        System.out.println("Select Analyzer (default = EnglishAnalyzer):");
        System.out.println("1: StandardAnalyzer");
        System.out.println("2: EnglishAnalyzer");
        System.out.println("3: SimpleAnalyzer");
        System.out.println("4: WhitespaceAnalyzer");
        System.out.println("5: CustomAnalyzer");
        System.out.print("> ");
        String input = sc.nextLine().trim();
        return switch (input) {
            case "1" -> new StandardAnalyzer();
            case "3" -> new SimpleAnalyzer();
            case "4" -> new WhitespaceAnalyzer();
            case "5" -> getCranfieldAnalyzer();
            default -> new EnglishAnalyzer();
        };
    }

    private static MultiFieldQueryParser makeParser(Analyzer analyzer, float titleBoost, float bodyBoost) {
        String[] fields = {"title", "body"};
        Map<String, Float> boosts = Map.of("title", titleBoost, "body", bodyBoost);
        return new MultiFieldQueryParser(fields, analyzer, boosts);
    }

    public static void interactiveSearch(IndexSearcher searcher, Analyzer analyzer, Scanner sc, float titleBoost, float bodyBoost) throws IOException {
        MultiFieldQueryParser parser = makeParser(analyzer, titleBoost, bodyBoost);
        System.out.println("Enter queries. Type ':q' to return to menu.");
        while (true) {
            System.out.print("\nQuery: ");
            String line = sc.nextLine().replaceAll("[\\?\\*]", " ");;
            if (line == null || line.equals(":q")) break;
            line = line.trim();
            if (line.isEmpty()) continue;

            // optional filters
            System.out.print("Filter by author (optional): ");
            String authorFilter = sc.nextLine().trim();
            System.out.print("Filter by title keyword (optional): ");
            String titleFilter = sc.nextLine().trim();

            Query mainQuery;
            try {
                mainQuery = parser.parse(line);
            } catch (ParseException e) {
                System.err.println("Failed to parse query: " + e.getMessage());
                continue;
            }

            BooleanQuery.Builder combined = new BooleanQuery.Builder();
            combined.add(mainQuery, BooleanClause.Occur.MUST);
            if (!authorFilter.isEmpty()) {
                combined.add(new TermQuery(new Term("author", authorFilter)), BooleanClause.Occur.FILTER);
            }
            if (!titleFilter.isEmpty()) {
                combined.add(new TermQuery(new Term("title", titleFilter)), BooleanClause.Occur.FILTER);
            }

            TopDocs topDocs = searcher.search(combined.build(), 10);
            System.out.println("Total hits (approx): " + topDocs.totalHits);
            int rank = 1;
            for (ScoreDoc sd : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(sd.doc);
                String snippet = doc.get("body");
                if (snippet != null && snippet.length() > 200) snippet = snippet.substring(0, 200) + "...";
                System.out.printf("%2d. id=%s score=%.4f title=%s\n    %s\n",
                        rank, doc.get("id"), sd.score, doc.get("title"), snippet);
                rank++;
            }
        }
    }

    public static String analyzeQuery(String text, Analyzer analyzer) throws IOException {
        try (TokenStream tokenStream = analyzer.tokenStream("", text)) {
            CharTermAttribute attr = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();
            StringBuilder sb = new StringBuilder();
            while (tokenStream.incrementToken()) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(attr.toString());
            }
            tokenStream.end();
            return sb.toString();
        }
    }

    public static LinkedHashMap<String, String> loadQueries(String queriesFile, Analyzer analyzer) throws IOException {
        LinkedHashMap<String, String> queries = new LinkedHashMap<>();
        int id = 1;

        try (BufferedReader br = new BufferedReader(new FileReader(queriesFile))) {
            String line;
            boolean inW = false;
            StringBuilder sb = new StringBuilder();

            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith(".I")) {
                    if (!sb.isEmpty()) {
                        String analyzedQuery = analyzeQuery(sb.toString().trim(), analyzer);
                        queries.put(String.valueOf(id), analyzedQuery);
                        id++;
                    }
                    sb.setLength(0);
                    inW = false;
                } else if (line.equals(".W")) {
                    inW = true;
                } else if (inW) {
                    sb.append(line).append(' ');
                }
            }

            if (!sb.isEmpty()) {
                String analyzedQuery = analyzeQuery(sb.toString().trim(), analyzer);
                queries.put(String.valueOf(id), analyzedQuery);
            }
        }
        return queries;
    }

    public static Map<String, Map<String, Integer>> loadQrels(String qrelFile) throws IOException {
        Map<String, Map<String, Integer>> qrels = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(qrelFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] parts = line.split("\\s+");
                if (parts.length < 4) continue;
                String qid = parts[0];
                String docid = parts[2];
                int rel = Integer.parseInt(parts[3]);
                qrels.computeIfAbsent(qid, k -> new HashMap<>()).put(docid, rel);
            }
        }
        return qrels;
    }

    public static void generateTrecResults(IndexSearcher searcher, Analyzer analyzer, String queriesFile, String outputFile,
                                           int topK, float titleBoost, float bodyBoost) throws Exception {
        LinkedHashMap<String, String> queries = loadQueries(queriesFile,analyzer);
        MultiFieldQueryParser parser = makeParser(analyzer, titleBoost, bodyBoost);

        try (PrintWriter pw = new PrintWriter(new FileWriter(outputFile))) {
            for (Map.Entry<String, String> e : queries.entrySet()) {
                String qid = e.getKey();
                String text = e.getValue();
                String safeQuery = text.replaceAll("[\\?\\*]", " ");
                Query q = parser.parse(safeQuery);
                TopDocs topDocs = searcher.search(q, topK);
                int rank = 1;
                for (ScoreDoc sd : topDocs.scoreDocs) {
                    Document doc = searcher.storedFields().document(sd.doc);
                    String docId = doc.get("id");
                    pw.printf("%s Q0 %s %d %.6f cranLucene\n", qid, docId, rank, sd.score);
                    rank++;
                }
            }
        }
        System.out.println("Wrote TREC results to: " + outputFile);
    }

    public static IndexSearcher makeSearcherWithSimilarity(IndexReader reader, int simChoice) {
        IndexSearcher searcher = new IndexSearcher(reader);
        switch (simChoice) {
            case 1: // Classic TF-IDF
                searcher.setSimilarity(new ClassicSimilarity());
                break;
            case 3: // LM Dirichlet
                searcher.setSimilarity(new LMDirichletSimilarity(1500)); // mu default ~1500
                break;
            case 4: // LM Jelinek-Mercer
                searcher.setSimilarity(new LMJelinekMercerSimilarity(0.7f)); // lambda default 0.7
                break;
            default: // BM25
                searcher.setSimilarity(new BM25Similarity()); // default k1=1.2, b=0.75
                break;
        }
        return searcher;
    }

    public static Analyzer getCranfieldAnalyzer() {
        List<String> cranStopwords = Arrays.asList(
                "a", "about", "above", "after", "again", "against", "all", "almost", "alone",
                "along", "already", "also", "although", "always", "among", "an", "and", "another",
                "any", "anybody", "anyone", "anything", "anywhere", "are", "as", "at", "be",
                "because", "been", "before", "being", "between", "both", "but", "by", "can",
                "could", "did", "do", "does", "doing", "down", "during", "each", "few", "for",
                "from", "further", "had", "has", "have", "having", "he", "her", "here", "hers",
                "him", "his", "how", "i", "if", "in", "into", "is", "it", "its", "itself", "just",
                "me", "more", "most", "my", "myself", "no", "nor", "not", "of", "off", "on",
                "once", "only", "or", "other", "our", "ours", "ourselves", "out", "over", "own",
                "same", "she", "should", "so", "some", "such", "than", "that", "the", "their",
                "theirs", "them", "themselves", "then", "there", "these", "they", "this", "those",
                "through", "to", "too", "under", "until", "up", "very", "was", "we", "were",
                "what", "when", "where", "which", "while", "who", "whom", "why", "with", "you",
                "your", "yours", "yourself", "yourselves"
        );

        CharArraySet stopSet = new CharArraySet(cranStopwords, true);

        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                Tokenizer tokenizer = new StandardTokenizer();

                TokenStream tokenStream = new LowerCaseFilter(tokenizer);
                tokenStream = new ASCIIFoldingFilter(tokenStream);
                tokenStream = new StopFilter(tokenStream, stopSet);
                tokenStream = new PorterStemFilter(tokenStream);

                return new TokenStreamComponents(tokenizer, tokenStream);
            }
        };
    }

    public static void runAllCombinations(String cranFile, String queriesFile, String qrelsFile) throws Exception {
        List<Analyzer> analyzers = List.of(
                new StandardAnalyzer(),
                new EnglishAnalyzer(),
                new SimpleAnalyzer(),
                new WhitespaceAnalyzer(),
                getCranfieldAnalyzer()
        );

        String[] analyzerNames = {"StandardAnalyzer", "EnglishAnalyzer", "SimpleAnalyzer", "WhitespaceAnalyzer", "CustomAnalyzer"};
        int[] similarities = {1, 2, 3, 4};
        String[] simNames = {"TFIDF", "BM25", "LMDirichlet", "LMJelinekMercer"};

        float[][] boostConfigs = {
                {1.0f, 1.0f},
                {2.0f, 1.0f},
                {1.0f, 2.0f}
        };

        List<CranFieldDocument> docs = parseCranField(new File(cranFile));

        for (int i = 0; i < analyzers.size(); i++) {
            Analyzer analyzer = analyzers.get(i);
            String analyzerName = analyzerNames[i];

            for (int s = 0; s < similarities.length; s++) {
                int simChoice = similarities[s];
                String simName = simNames[s];

                for (float[] boost : boostConfigs) {
                    float titleBoost = boost[0];
                    float contentBoost = boost[1];

                    String boostTag = String.format("t%.0f_c%.0f", titleBoost, contentBoost);
                    String indexDirName = ROOT_INDEX_PATH + "index_" + analyzerName + "_" + simName + "_" + boostTag;
                    Path indexPath = Paths.get(indexDirName);

                    System.out.println("\n=== Running combo: " + analyzerName + " + " + simName + " + Boost(" + titleBoost + "," + contentBoost + ") ===");

                    buildIndex(indexPath, docs, analyzer);

                    try (IndexReader reader = DirectoryReader.open(FSDirectory.open(indexPath))) {
                        IndexSearcher searcher = makeSearcherWithSimilarity(reader, simChoice);

                        String resultsPath = "output/results";
                        new File(resultsPath).mkdirs();

                        String resultFile = String.format(resultsPath + "/%s_%s_%s_results.txt", analyzerName, simName, boostTag);

                        String internalEvalPath = "output/internalEval";
                        new File(internalEvalPath).mkdirs();
                        String evalFile = String.format(internalEvalPath + "/%s_%s_%s_eval.txt", analyzerName, simName, boostTag);

                        System.out.println("Running internal eval for " + analyzerName + " + " + simName + " + " + boostTag);
                        System.out.printf("Indexing docs: %d | Analyzer: %s | Similarity: %s | Boost(title=%.1f, content=%.1f)%n",
                                docs.size(), analyzerName, simName, titleBoost, contentBoost);

                        generateTrecResults(searcher, analyzer, queriesFile, resultFile, DEFAULT_TOP_K, titleBoost, contentBoost);
                        //runInternalEvaluation(searcher, analyzer, queriesFile, qrelsFile, DEFAULT_TOP_K, titleBoost, contentBoost);

                        File evalDetails = new File("evaluation_details.txt");
                        if (evalDetails.exists()) {
                            evalDetails.renameTo(new File(evalFile));
                        }

                        String trecEvalPath = "output/trec_eval";
                        new File(trecEvalPath).mkdirs();

                        String trecEvalOutput = String.format(trecEvalPath + "/%s_%s_%s_trec.txt", analyzerName, simName, boostTag);

                        ProcessBuilder pb = new ProcessBuilder("trec_eval", qrelsFile, resultFile);
                        Process p = pb.start();

                        try (BufferedReader readerCmd = new BufferedReader(new InputStreamReader(p.getInputStream()));
                             PrintWriter writer = new PrintWriter(new FileWriter(trecEvalOutput))) {
                            String line;
                            while ((line = readerCmd.readLine()) != null) {
                                writer.println(line);
                            }
                        }
                        p.waitFor();
                        System.out.println("TREC_EVAL Output: " + trecEvalOutput);
                    }
                }
            }
        }

    }


    public static void main(String[] args) throws Exception {
        File cran = new File(CRAN_FILE);
        if (!cran.exists()) {
            System.err.println("ERROR: cran.all not found in project");
            return;
        }

        Scanner sc = new Scanner(System.in);

        mainLoop:
        while (true) {
            System.out.println("\n--- Menu ---");
            System.out.println("1: Interactive search");
            System.out.println("2: Run all Analyzer+Similarity Evaluation");
            System.out.println("3: Exit");
            System.out.print("> ");
            String choice = sc.nextLine().trim();
            switch (choice) {
                case "1":
                    Analyzer analyzer = chooseAnalyzer(sc);
                    System.out.println("Parsing cran.all ...");
                    List<CranFieldDocument> docs = parseCranField(cran);
                    System.out.println("Parsed documents: " + docs.size());
                    System.out.println("Building index using analyzer: " + analyzer.getClass().getSimpleName());
                    buildIndex(INTERACTIVE_INDEX_PATH, docs, analyzer);
                    System.out.println("Index built at: " + INTERACTIVE_INDEX_PATH.toAbsolutePath());


                    System.out.print("Title boost (default 2.0) : ");
                    String tb = sc.nextLine().trim();
                    float titleBoost = tb.isEmpty() ? 2.0f : Float.parseFloat(tb);

                    System.out.print("Body boost (default 1.0)  : ");
                    String bb = sc.nextLine().trim();
                    float bodyBoost = bb.isEmpty() ? 1.0f : Float.parseFloat(bb);

                    System.out.println("Choose Scoring Method (default 2 = BM25):");
                    System.out.println("1: TF-IDF (ClassicSimilarity)");
                    System.out.println("2: BM25 (BM25Similarity)");
                    System.out.println("3: LM Dirichlet (LMDirichletSimilarity)");
                    System.out.println("4: LM Jelinek-Mercer (LMJelinekMercerSimilarity)");
                    System.out.print("> ");
                    String simChoiceStr = sc.nextLine().trim();
                    int simChoice = simChoiceStr.isEmpty() ? 2 : Integer.parseInt(simChoiceStr);

                    try (IndexReader reader = DirectoryReader.open(FSDirectory.open(INTERACTIVE_INDEX_PATH))) {
                        IndexSearcher searcher = makeSearcherWithSimilarity(reader, simChoice);
                        interactiveSearch(searcher, analyzer, sc, titleBoost, bodyBoost);

                        generateTrecResults(searcher, analyzer, "cran/cran.qry", "output/interactive/results.txt", 100, titleBoost, bodyBoost);
                    }

                    break;
                case "2":
                    runAllCombinations(CRAN_FILE, "cran/cran.qry", "cran/cranqrel");
                    break;
                default:
                    break mainLoop;
            }

        }

        System.out.println("Exit.");
    }
}
