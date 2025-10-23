package org.cranfield;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class aggragator {

    public static void main(String[] args) throws IOException {
        String trecEvalDir = "output/trec_eval";  // folder where all trec_eval results are saved
        String outputCSV = "output/trec_eval_summary.csv";  // final summary file

        // Metrics to extract
        List<String> metrics = List.of(
                "map",
                "P_10",
                "Rprec",
                "bpref",
                "recip_rank",
                "iprec_at_recall_0.00"  // interpolated precision at recall 0.00
        );

        // Prepare CSV header
        try (PrintWriter writer = new PrintWriter(new FileWriter(outputCSV))) {
            writer.println("Analyzer,Similarity,MAP,P@10,R-Prec,bpref,recip_rank,InterpolatedPrecision");

            // Loop through each trec_eval result file
            Files.list(Paths.get(trecEvalDir))
                    .filter(path -> path.toString().endsWith(".txt"))
                    .forEach(path -> {
                        try {
                            Map<String, String> values = parseTrecEvalFile(path, metrics);
                            String fileName = path.getFileName().toString();

                            // Extract analyzer and similarity names from file
                            String[] parts = fileName.replace("_trec.txt", "").split("_");
                            String analyzer = parts[0];
                            String similarity = parts.length > 1 ? parts[1] : "Unknown";

                            // Write a row in CSV
                            writer.printf("%s,%s,%s,%s,%s,%s,%s,%s%n",
                                    analyzer,
                                    similarity,
                                    values.getOrDefault("map", ""),
                                    values.getOrDefault("P_10", ""),
                                    values.getOrDefault("Rprec", ""),
                                    values.getOrDefault("bpref", ""),
                                    values.getOrDefault("recip_rank", ""),
                                    values.getOrDefault("iprec_at_recall_0.00", "")
                            );

                        } catch (IOException e) {
                            System.err.println("Error reading file: " + path + " - " + e.getMessage());
                        }
                    });
        }

        System.out.println("✅ TREC Eval summary CSV generated at: " + outputCSV);
    }

    // Helper function to parse required metrics from a single TREC eval file
    private static Map<String, String> parseTrecEvalFile(Path file, List<String> metrics) throws IOException {
        Map<String, String> values = new HashMap<>();
        Pattern pattern = Pattern.compile("^(\\S+)\\s+all\\s+(\\S+)$");

        try (BufferedReader br = Files.newBufferedReader(file)) {
            String line;
            while ((line = br.readLine()) != null) {
                Matcher matcher = pattern.matcher(line.trim());
                if (matcher.find()) {
                    String metric = matcher.group(1);
                    String value = matcher.group(2);
                    if (metrics.contains(metric)) {
                        values.put(metric, value);
                    }
                }
            }
        }
        return values;
    }
}
