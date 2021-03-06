package zemberek.normalization;

import org.antlr.v4.runtime.Token;
import zemberek.core.ScoredItem;
import zemberek.core.logging.Log;
import zemberek.core.turkish.TurkishAlphabet;
import zemberek.lm.DummyLanguageModel;
import zemberek.lm.LmVocabulary;
import zemberek.lm.NgramLanguageModel;
import zemberek.morphology.analysis.WordAnalysis;
import zemberek.morphology.analysis.WordAnalysisFormatter;
import zemberek.morphology.analysis.tr.TurkishMorphology;
import zemberek.morphology.structure.Turkish;
import zemberek.tokenizer.ZemberekLexer;
import zemberek.tokenizer.antlr.TurkishLexer;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class TurkishSpellChecker {

    TurkishMorphology morphology;
    WordAnalysisFormatter formatter = new WordAnalysisFormatter();
    CharacterGraphDecoder decoder;

    public TurkishSpellChecker(TurkishMorphology morphology) throws IOException {
        this.morphology = morphology;
        StemEndingGraph graph = new StemEndingGraph(morphology);
        decoder = new CharacterGraphDecoder(graph.stemGraph);
    }

    public TurkishSpellChecker(TurkishMorphology morphology,
                               CharacterGraph graph) throws IOException {
        this.morphology = morphology;
        decoder = new CharacterGraphDecoder(graph);
    }

    public boolean check(String input) {
        List<WordAnalysis> analyses = morphology.analyze(input);
        WordAnalysisFormatter.CaseType caseType = formatter.guessCase(input);
        for (WordAnalysis analysis : analyses) {
            if (analysis.isUnknown()) {
                continue;
            }
            if (formatter.canBeFormatted(analysis, caseType)) {
                String formatted = formatter.formatToCase(analysis, caseType);
                if (input.equals(formatted)) {
                    return true;
                }
            }
        }
        return false;
    }

    public List<String> suggestForWord(String word, NgramLanguageModel lm) {
        List<String> unRanked = getUnrankedSuggestions(word);
        return rankWithUnigramProbability(unRanked, lm);
    }

    private List<String> getUnrankedSuggestions(String word) {
        String normalized = TurkishAlphabet.INSTANCE.normalize(word).replaceAll("'", "");
        List<String> strings = decoder.getSuggestions(normalized);

        WordAnalysisFormatter.CaseType caseType = formatter.guessCase(word);
        if (caseType == WordAnalysisFormatter.CaseType.MIXED_CASE ||
                caseType == WordAnalysisFormatter.CaseType.LOWER_CASE) {
            caseType = WordAnalysisFormatter.CaseType.DEFAULT_CASE;
        }
        Set<String> results = new LinkedHashSet<>(strings.size());
        for (String string : strings) {
            List<WordAnalysis> analyses = morphology.analyze(string);
            for (WordAnalysis analysis : analyses) {
                if (analysis.isUnknown()) {
                    continue;
                }
                String formatted = formatter.formatToCase(analysis, caseType);
                results.add(formatted);
            }
        }
        return new ArrayList<>(results);
    }

    public List<String> suggestForWord(
            String word,
            String leftContext,
            String rightContext,
            NgramLanguageModel lm) {
        List<String> unRanked = getUnrankedSuggestions(word);
        if (lm.getOrder() < 2) {
            Log.warn("Language model order is 1. For context ranking it should be at least 2. " +
                    "Unigram ranking will be applied.");
            return suggestForWord(word, lm);
        }
        LmVocabulary vocabulary = lm.getVocabulary();
        List<ScoredItem<String>> results = new ArrayList<>(unRanked.size());
        for (String str : unRanked) {
            if (leftContext == null) {
                leftContext = vocabulary.getSentenceStart();
            } else {
                leftContext = normalizeForLm(leftContext);
            }
            if (rightContext == null) {
                rightContext = vocabulary.getSentenceEnd();
            } else {
                rightContext = normalizeForLm(rightContext);
            }
            String w = normalizeForLm(str);
            int wordIndex = vocabulary.indexOf(w);
            int leftIndex = vocabulary.indexOf(leftContext);
            int rightIndex = vocabulary.indexOf(rightContext);
            float score;
            if (lm.getOrder() == 2) {
                score = lm.getProbability(leftIndex, wordIndex) + lm.getProbability(wordIndex, rightIndex);
            } else {
                score = lm.getProbability(leftIndex, wordIndex, rightIndex);
            }
            results.add(new ScoredItem<>(str, score));
        }
        results.sort(ScoredItem.STRING_COMP_DESCENDING);
        return results.stream().map(s -> s.item).collect(Collectors.toList());

    }

    private String normalizeForLm(String s) {
        if (s.indexOf('\'') > 0) {
            return Turkish.capitalize(s);
        } else {
            return s.toLowerCase(Turkish.LOCALE);
        }
    }

    private static final NgramLanguageModel DUMMY_LM = new DummyLanguageModel();

    public List<String> suggestForWord(String word) {
        return suggestForWord(word, DUMMY_LM);
    }

    public CharacterGraphDecoder getDecoder() {
        return decoder;
    }

    public List<String> rankWithUnigramProbability(List<String> strings, NgramLanguageModel lm) {
        List<ScoredItem<String>> results = new ArrayList<>(strings.size());
        for (String string : strings) {
            String w = normalizeForLm(string);
            int wordIndex = lm.getVocabulary().indexOf(w);
            results.add(new ScoredItem<>(string, lm.getUnigramProbability(wordIndex)));
        }
        results.sort(ScoredItem.STRING_COMP_DESCENDING);
        return results.stream().map(s -> s.item).collect(Collectors.toList());
    }

    static final ZemberekLexer lexer = new ZemberekLexer(true);

    public static List<String> tokenizeForSpelling(String sentence) {
        List<Token> tokens = lexer.tokenizeAll(sentence);
        List<String> result = new ArrayList<>(tokens.size());
        for (Token token : tokens) {
            if (token.getType() == TurkishLexer.Unknown ||
                    token.getType() == TurkishLexer.UnknownWord ||
                    token.getType() == TurkishLexer.Punctuation) {
                continue;
            }
            String w = token.getText();
            if (token.getType() == TurkishLexer.TurkishWord) {
                w = w.toLowerCase(Turkish.LOCALE);
            } else if (token.getType() == TurkishLexer.TurkishWordWithApos) {
                w = Turkish.capitalize(w);
            }
            result.add(w);
        }
        return result;
    }
}
