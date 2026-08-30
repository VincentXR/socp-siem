package com.socp.search.config.query;

import java.util.ArrayList;
import java.util.List;

/** Small deterministic lexer used by {@link SplParser}; no backend semantics live here. */
public final class SplLexer {
    public enum Kind { WORD, STRING, OPERATOR, PIPE, LPAREN, RPAREN, EOF }

    public record Token(Kind kind, String text, int position) { }

    public List<Token> tokenize(String input) {
        String source = input == null ? "" : input;
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '|') { tokens.add(new Token(Kind.PIPE, "|", i++)); continue; }
            if (c == '(') { tokens.add(new Token(Kind.LPAREN, "(", i++)); continue; }
            if (c == ')') { tokens.add(new Token(Kind.RPAREN, ")", i++)); continue; }
            if (c == '"' || c == '\'') {
                int start = i++;
                StringBuilder value = new StringBuilder();
                boolean closed = false;
                while (i < source.length()) {
                    char current = source.charAt(i++);
                    if (current == '\\' && i < source.length()) {
                        value.append(source.charAt(i++));
                    } else if (current == c) {
                        closed = true;
                        break;
                    } else {
                        value.append(current);
                    }
                }
                if (!closed) throw new SplParseException("unterminated string", start);
                tokens.add(new Token(Kind.STRING, value.toString(), start));
                continue;
            }
            if (i + 1 < source.length()) {
                String two = source.substring(i, i + 2);
                if (two.equals(">=") || two.equals("<=") || two.equals("!=")) {
                    tokens.add(new Token(Kind.OPERATOR, two, i));
                    i += 2;
                    continue;
                }
            }
            if (c == '=' || c == '>' || c == '<') {
                tokens.add(new Token(Kind.OPERATOR, String.valueOf(c), i++));
                continue;
            }
            if (c == '*') {
                tokens.add(new Token(Kind.WORD, "*", i++));
                continue;
            }
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-' || c == '/' || c == ':') {
                int start = i++;
                while (i < source.length()) {
                    char current = source.charAt(i);
                    if (!(Character.isLetterOrDigit(current) || current == '_' || current == '.'
                            || current == '-' || current == '/' || current == ':' || current == '@')) break;
                    i++;
                }
                tokens.add(new Token(Kind.WORD, source.substring(start, i), start));
                continue;
            }
            throw new SplParseException("unexpected character '" + c + "'", i);
        }
        tokens.add(new Token(Kind.EOF, "", source.length()));
        return List.copyOf(tokens);
    }
}
