package com.socp.rule;

import com.google.re2j.Pattern;
import com.socp.rule.regex.SafeRegex;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SafeRegexParityTest {
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
