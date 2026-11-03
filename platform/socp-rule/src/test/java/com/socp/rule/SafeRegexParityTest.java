package com.socp.rule;

import com.google.re2j.Pattern;
import com.socp.rule.regex.SafeRegex;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SafeRegexParityTest {
    @Test
    void necessaryLiteralFilterPreservesAlternationQuantifiersEscapesAndUnicode() {
        var expressions = new java.util.ArrayList<>(List.of(
                "(?i)(useradd|adduser).*(sudo|wheel|admin)|usermod.*(-aG|--append)",
                "(?i)powershell.*(-enc|-encodedcommand|downloadstring|invoke-expression)",
                "(?i)(schtasks.*(/create|create)|scheduled task.*created)",
                "(?i)(bash -c|sh -c|curl.*\\\\|.*sh|wget.*\\\\|.*sh)",
                "(a|).*b", "(a|b)*c", "(a|b)?c", "ab*c", "ab+c", "ab{0}c", "ab{0,2}c",
                "a(b|c)?", "(a.*b)|(b.*a)", "a\\Q|(()[\\E", "a\\x41", "(?-i)a.*B", "(?s)a.*b",
                "(?i)k.*s", "^.*(a|b)$", "a.*|", "a.*|.*", "(a|b)\\?", "a\\*b.*c"));
        for (String head : List.of("a", "ab", "(a|b)", "(a|ab)", "a\\|b", "a\\*b")) {
            for (String tail : List.of(".*b", ".*(a|b)", ".*b|b.*a", "*b", "?b", "{0,2}b")) {
                expressions.add(head + tail);
                expressions.add("^(" + head + tail + ")$");
            }
        }
        var examples = new java.util.ArrayList<>(List.of("", "powershell -enc", "USERADD sudo", "usermod --append",
                "scheduled task created", "a|b", "a*b c", "a|(()[", "\u212a \u017f", "K\nS", "é文"));
        var alphabet = new char[]{'a', 'b', 'A', 'B', '\n'};
        for (int length = 1; length <= 5; length++) {
            int combinations = (int) Math.pow(alphabet.length, length);
            for (int n = 0; n < combinations; n++) {
                int value = n;
                StringBuilder input = new StringBuilder();
                for (int i = 0; i < length; i++) { input.append(alphabet[value % alphabet.length]); value /= alphabet.length; }
                examples.add(input.toString());
            }
        }
        for (String expression : expressions) {
            var expected = Pattern.compile(expression, Pattern.CASE_INSENSITIVE);
            var actual = SafeRegex.compileCaseInsensitive(expression);
            assertEquals(expected.matcher("").find(), actual.find(null), expression);
            for (String value : examples) {
                assertEquals(expected.matcher(value).find(), actual.find(value), expression + ": " + value);
            }
        }
    }

    @Test
    void literalFastPathPreservesRe2FindAndUnicodeFolding() {
        var expressions = List.of("(?i)mimikatz|lsass|/etc/shadow", "(?i)(rdp|mstsc|3389)",
                "(?i)bash -c|iex \\(", "a\\|b|c", "", "a|", "^foo$", "a.*b", "(foo)|(bar)",
                "[a-z]+", "(?-i)ABC", "\\d+", "(?i)k|s", "é|文");
        var examples = List.of("", "none", "xx MIMIKATZ zz", "xx RDP yy", "iex (", "a|b",
                "\u212a", "\u017f", "é文", "ABC", "foo\nbar", "x3389y");
        Random random = new Random(719);
        for (String expression : expressions) {
            var expected = Pattern.compile(expression, Pattern.CASE_INSENSITIVE);
            var actual = SafeRegex.compileCaseInsensitive(expression);
            for (String value : examples) assertEquals(expected.matcher(value).find(), actual.find(value), expression + ": " + value);
            for (int sample = 0; sample < 300; sample++) {
                StringBuilder value = new StringBuilder();
                for (int i = 0; i < 64; i++) value.append((char) random.nextInt(sample % 2 == 0 ? 128 : 400));
                assertEquals(expected.matcher(value).find(), actual.find(value.toString()), expression);
            }
        }
    }
}
