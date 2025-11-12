package org.cranfield;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

public class aggragator {

    public static void main(String[] args) throws IOException {
        String trecEvalDir = "output/trec_eval";
        String outputCSV = "output/trec_eval_summary.csv";

        List<String> metrics = List.of(
                "map",
                "P_10",
                "Rprec",
                "bpref",
                "recip_rank",
                "iprec_at_recall_0.00",
                "num_rel",
                "num_rel_ret"
        );

        List<Map<String, String>> allRows = new ArrayList<>();

        Files.list(Paths.get(trecEvalDir))
                .filter(path -> path.toString().endsWith(".txt"))
                .forEach(path -> {
                    try {
                        Map<String, String> values = parseTrecEvalFile(path, metrics);
                        String fileName = path.getFileName().toString().replace("_trec.txt", "");
                        String[] parts = fileName.split("_");

                        // Basic analyzer & similarity
                        String analyzer = parts[0];
                        String similarity = parts.length > 1 ? parts[1] : "Unknown";

                        String t1 = "", c1 = "", t2 = "", c2 = "";
                        for (String part : parts) {
                            switch (part.toLowerCase()) {
                                case "t1" -> t1 = "1";
                                case "c1" -> c1 = "1";
                                case "t2" -> t2 = "1";
                                case "c2" -> c2 = "1";
                            }
                        }

                        double recall = 0.0;
                        if (values.containsKey("num_rel") && values.containsKey("num_rel_ret")) {
                            try {
                                double numRel = Double.parseDouble(values.get("num_rel"));
                                double numRelRet = Double.parseDouble(values.get("num_rel_ret"));
                                if (numRel > 0) {
                                    recall = numRelRet / numRel;
                                }
                            } catch (NumberFormatException ignored) {}
                        }

                        Map<String, String> row = new LinkedHashMap<>();
                        row.put("Analyzer", analyzer);
                        row.put("Similarity", similarity);
                        row.put("T1", t1);
                        row.put("C1", c1);
                        row.put("T2", t2);
                        row.put("C2", c2);
                        row.put("MAP", values.getOrDefault("map", ""));
                        row.put("P@10", values.getOrDefault("P_10", ""));
                        row.put("R-Prec", values.getOrDefault("Rprec", ""));
                        row.put("bpref", values.getOrDefault("bpref", ""));
                        row.put("recip_rank", values.getOrDefault("recip_rank", ""));
                        row.put("InterpolatedPrecision", values.getOrDefault("iprec_at_recall_0.00", ""));
                        row.put("Recall", String.valueOf(recall));

                        allRows.add(row);

                    } catch (IOException e) {
                        System.err.println("Error reading file: " + path + " - " + e.getMessage());
                    }
                });

        allRows.sort((a, b) -> {
            double mapA = a.get("MAP").isEmpty() ? 0.0 : Double.parseDouble(a.get("MAP"));
            double mapB = b.get("MAP").isEmpty() ? 0.0 : Double.parseDouble(b.get("MAP"));
            return Double.compare(mapB, mapA); // descending
        });

        try (PrintWriter writer = new PrintWriter(new FileWriter(outputCSV))) {
            writer.println("Analyzer,Similarity,T1,C1,T2,C2,MAP,P@10,R-Prec,bpref,recip_rank,InterpolatedPrecision,Recall");
            for (Map<String, String> row : allRows) {
                writer.println(row.values().stream().collect(Collectors.joining(",")));
            }
        }

        System.out.println("✅ TREC Eval summary CSV generated at: " + outputCSV);
    }

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
