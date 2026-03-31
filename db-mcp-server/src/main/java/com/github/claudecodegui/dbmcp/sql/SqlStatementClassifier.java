package com.github.claudecodegui.dbmcp.sql;

import java.util.Locale;

public final class SqlStatementClassifier {

    private SqlStatementClassifier() {
    }

    public static SqlClassification classify(String sql) {
        if (sql == null || sql.isBlank()) {
            return new SqlClassification(SqlOperationType.UNKNOWN, false, false, false);
        }

        String normalized = stripLeadingComments(sql).trim();
        String compactLower = normalized.toLowerCase(Locale.ROOT);
        boolean usesCte = compactLower.startsWith("with ");
        boolean hasWhere = compactLower.contains(" where ");

        String firstToken = firstToken(normalized);
        SqlOperationType operationType = switch (firstToken) {
            case "select", "with" -> SqlOperationType.SELECT;
            case "insert", "update", "delete", "merge" -> SqlOperationType.CHANGE;
            case "create", "alter", "drop", "truncate", "rename", "comment" -> SqlOperationType.DDL;
            default -> SqlOperationType.UNKNOWN;
        };

        boolean destructive = compactLower.contains(" drop ")
                || compactLower.startsWith("drop ")
                || compactLower.startsWith("truncate ")
                || ((firstToken.equals("update") || firstToken.equals("delete")) && !hasWhere);

        return new SqlClassification(operationType, destructive, hasWhere, usesCte);
    }

    public static boolean containsMultipleStatements(String sql) {
        if (sql == null) {
            return false;
        }

        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        int statements = 0;
        boolean seenNonWhitespace = false;

        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (ch == '\n') {
                    inLineComment = false;
                }
                continue;
            }
            if (inBlockComment) {
                if (ch == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (!inSingleQuote && !inDoubleQuote) {
                if (ch == '-' && next == '-') {
                    inLineComment = true;
                    i++;
                    continue;
                }
                if (ch == '/' && next == '*') {
                    inBlockComment = true;
                    i++;
                    continue;
                }
            }
            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            }

            if (inSingleQuote || inDoubleQuote) {
                continue;
            }

            if (!Character.isWhitespace(ch)) {
                seenNonWhitespace = true;
            }

            if (ch == ';') {
                if (seenNonWhitespace) {
                    statements++;
                    seenNonWhitespace = false;
                }
            }
        }

        if (seenNonWhitespace) {
            statements++;
        }
        return statements > 1;
    }

    private static String stripLeadingComments(String sql) {
        String current = sql;
        boolean changed;
        do {
            changed = false;
            String trimmed = current.stripLeading();
            if (trimmed.startsWith("--")) {
                int end = trimmed.indexOf('\n');
                current = end >= 0 ? trimmed.substring(end + 1) : "";
                changed = true;
            } else if (trimmed.startsWith("/*")) {
                int end = trimmed.indexOf("*/");
                current = end >= 0 ? trimmed.substring(end + 2) : "";
                changed = true;
            } else {
                current = trimmed;
            }
        } while (changed);
        return current;
    }

    private static String firstToken(String sql) {
        int end = 0;
        while (end < sql.length() && Character.isLetter(sql.charAt(end))) {
            end++;
        }
        return sql.substring(0, end).toLowerCase(Locale.ROOT);
    }
}
