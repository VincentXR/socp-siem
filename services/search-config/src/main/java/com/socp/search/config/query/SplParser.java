package com.socp.search.config.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parser for the intentionally small, documented SPL subset. */
public final class SplParser {
    private final SplLexer lexer;
    private final QuerySemanticAnalyzer semanticAnalyzer;

    public SplParser() { this(new SplLexer(), QuerySemanticAnalyzer.standard()); }

    public SplParser(SplLexer lexer) { this(lexer, QuerySemanticAnalyzer.standard()); }

    public SplParser(SplLexer lexer, QuerySemanticAnalyzer semanticAnalyzer) {
        this.lexer = lexer;
        this.semanticAnalyzer = semanticAnalyzer;
    }

    public SearchQueryAst parse(String query) {
        List<SplLexer.Token> tokens = lexer.tokenize(query);
        Parser parser = new Parser(tokens);
        FilterExpression filter = parser.parseFilter();
        List<PipelineCommand> commands = new ArrayList<>();
        while (parser.accept(SplLexer.Kind.PIPE)) commands.add(parser.parseCommand());
        parser.expect(SplLexer.Kind.EOF, "unexpected token");
        return semanticAnalyzer.analyze(new SearchQueryAst(filter, commands));
    }

    private static final class Parser {
        private final List<SplLexer.Token> tokens;
        private int index;

        private Parser(List<SplLexer.Token> tokens) { this.tokens = tokens; }

        private FilterExpression parseFilter() {
            if (peek().kind() == SplLexer.Kind.PIPE || peek().kind() == SplLexer.Kind.EOF) {
                return FilterExpression.MatchAll.INSTANCE;
            }
            return parseOr();
        }

        private FilterExpression parseOr() {
            List<FilterExpression> terms = new ArrayList<>();
            terms.add(parseAnd());
            while (isWord("OR")) {
                advance();
                terms.add(parseAnd());
            }
            return terms.size() == 1 ? terms.getFirst() : new FilterExpression.Or(terms);
        }

        private FilterExpression parseAnd() {
            List<FilterExpression> terms = new ArrayList<>();
            terms.add(parsePrimary());
            while (isWord("AND") || startsPrimary(peek())) {
                if (isWord("AND")) advance();
                terms.add(parsePrimary());
            }
            return terms.size() == 1 ? terms.getFirst() : new FilterExpression.And(terms);
        }

        private FilterExpression parsePrimary() {
            if (isWord("*")) {
                advance();
                return FilterExpression.MatchAll.INSTANCE;
            }
            if (accept(SplLexer.Kind.LPAREN)) {
                FilterExpression nested = parseOr();
                expect(SplLexer.Kind.RPAREN, "missing ')' in filter");
                return nested;
            }
            SplLexer.Token field = expect(SplLexer.Kind.WORD, "expected a field name");
            SplLexer.Token op = peek();
            FilterExpression.Operator operator;
            if (op.kind() == SplLexer.Kind.OPERATOR) {
                advance();
                operator = operator(op.text(), op.position());
            } else if (isWord("CONTAINS")) {
                advance();
                operator = FilterExpression.Operator.CONTAINS;
            } else {
                throw error("expected a comparison operator", op);
            }
            SplLexer.Token value = peek();
            if (value.kind() != SplLexer.Kind.WORD && value.kind() != SplLexer.Kind.STRING) {
                throw error("expected a comparison value", value);
            }
            advance();
            return new FilterExpression.Comparison(field.text(), operator, value.text(), field.position());
        }

        private PipelineCommand parseCommand() {
            SplLexer.Token command = expect(SplLexer.Kind.WORD, "expected a pipeline command");
            return switch (command.text().toLowerCase(Locale.ROOT)) {
                case "top" -> {
                    String field = expect(SplLexer.Kind.WORD, "top requires a field").text();
                    int limit = optionalInteger(10, "top limit");
                    yield new PipelineCommand.Top(field, limit);
                }
                case "count" -> {
                    if (!acceptWord("by")) throw error("count requires 'by <field>'", peek());
                    yield new PipelineCommand.CountBy(expect(SplLexer.Kind.WORD, "count requires a field").text());
                }
                case "head" -> new PipelineCommand.Head(optionalIntegerRequired("head limit"));
                case "limit" -> new PipelineCommand.Limit(optionalIntegerRequired("limit"));
                case "timechart" -> new PipelineCommand.Timechart();
                case "sort" -> {
                    String field = expect(SplLexer.Kind.WORD, "sort requires a field").text();
                    PipelineCommand.SortOrder order = PipelineCommand.SortOrder.ASC;
                    if (peek().kind() == SplLexer.Kind.WORD) {
                        String text = peek().text().toLowerCase(Locale.ROOT);
                        if (text.equals("asc") || text.equals("desc")) {
                            advance();
                            order = "desc".equals(text) ? PipelineCommand.SortOrder.DESC : PipelineCommand.SortOrder.ASC;
                        }
                    }
                    yield new PipelineCommand.Sort(field, order);
                }
                default -> throw error("unsupported pipeline command '" + command.text() + "'", command);
            };
        }

        private int optionalInteger(int fallback, String label) {
            if (peek().kind() != SplLexer.Kind.WORD) return fallback;
            return integer(label);
        }

        private int optionalIntegerRequired(String label) {
            return integer(label);
        }

        private int integer(String label) {
            SplLexer.Token token = expect(SplLexer.Kind.WORD, label + " must be an integer");
            try {
                int value = Integer.parseInt(token.text());
                if (value < 1 || value > 100_000) throw new NumberFormatException();
                return value;
            } catch (NumberFormatException failure) {
                throw new SplParseException(label + " must be between 1 and 100000", token.position());
            }
        }

        private FilterExpression.Operator operator(String text, int position) {
            return switch (text) {
                case "=" -> FilterExpression.Operator.EQ;
                case "!=" -> FilterExpression.Operator.NE;
                case ">=" -> FilterExpression.Operator.GE;
                case ">" -> FilterExpression.Operator.GT;
                case "<=" -> FilterExpression.Operator.LE;
                case "<" -> FilterExpression.Operator.LT;
                default -> throw new SplParseException("unsupported operator '" + text + "'", position);
            };
        }

        private boolean startsPrimary(SplLexer.Token token) {
            return token.kind() == SplLexer.Kind.LPAREN
                    || (token.kind() == SplLexer.Kind.WORD && !isWord("OR", token) && !isWord("AND", token));
        }

        private boolean isWord(String value) { return isWord(value, peek()); }

        private boolean isWord(String value, SplLexer.Token token) {
            return token.kind() == SplLexer.Kind.WORD && value.equalsIgnoreCase(token.text());
        }

        private boolean acceptWord(String value) {
            if (!isWord(value)) return false;
            advance();
            return true;
        }

        private boolean accept(SplLexer.Kind kind) {
            if (peek().kind() != kind) return false;
            advance();
            return true;
        }

        private SplLexer.Token expect(SplLexer.Kind kind, String message) {
            SplLexer.Token token = peek();
            if (token.kind() != kind) throw error(message, token);
            return advance();
        }

        private SplLexer.Token advance() { return tokens.get(index++); }

        private SplLexer.Token peek() { return tokens.get(index); }

        private SplParseException error(String message, SplLexer.Token token) {
            return new SplParseException(message, token.position());
        }
    }
}
