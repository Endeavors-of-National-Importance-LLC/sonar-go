/*
 * SonarSource Go
 * Copyright (C) SonarSource Sàrl
 * mailto:info AT sonarsource DOT com
 *
 * You can redistribute and/or modify this program under the terms of
 * the Sonar Source-Available License Version 1, as published by SonarSource Sàrl.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the Sonar Source-Available License for more details.
 *
 * You should have received a copy of the Sonar Source-Available License
 * along with this program; if not, see https://sonarsource.com/license/ssal/
 */
package org.sonar.plugins.go.api.checks;

public final class GeneratedCodeDetector {

  /**
   * Prevents instantiation of this utility class.
   */
  private GeneratedCodeDetector() {
  }

  /**
   * Determines whether a Go source file carries the standard generated-source marker.
   * @param fileContent the complete source file content
   * @return {@code true} when a valid marker appears before source code
   */
  public static boolean isGenerated(String fileContent) {
    var preamble = new CommentPreamble();
    var lineStart = 0;
    while (lineStart < fileContent.length()) {
      var lineEnd = fileContent.indexOf('\n', lineStart);
      if (lineEnd < 0) {
        lineEnd = fileContent.length();
      }
      var line = fileContent.substring(lineStart, lineEnd).replace("\r", "");
      var lineType = preamble.classify(line);
      if (lineType == PreambleLineType.GENERATED_MARKER) {
        return true;
      }
      if (lineType == PreambleLineType.CODE) {
        return false;
      }
      lineStart = lineEnd + 1;
    }
    return false;
  }

  private enum PreambleLineType {
    GENERATED_MARKER,
    COMMENT_OR_BLANK,
    CODE
  }

  private static final class CommentPreamble {

    private static final String GENERATED_CODE_PREFIX = "// Code generated ";
    private static final String GENERATED_CODE_SUFFIX = " DO NOT EDIT.";

    private boolean inBlockComment;

    PreambleLineType classify(String line) {
      if (inBlockComment && isGeneratedCodeMarker(line)) {
        return PreambleLineType.GENERATED_MARKER;
      }
      var index = 0;
      while (index < line.length()) {
        if (inBlockComment) {
          index = skipBlockComment(line, index);
          if (inBlockComment) {
            return PreambleLineType.COMMENT_OR_BLANK;
          }
        }
        index = skipWhitespace(line, index);
        var lineType = classifyNonBlockComment(line, index);
        if (lineType != null) {
          return lineType;
        }
        inBlockComment = true;
        index += 2;
      }
      return PreambleLineType.COMMENT_OR_BLANK;
    }

    private static PreambleLineType classifyNonBlockComment(String line, int index) {
      if (index == line.length()) {
        return PreambleLineType.COMMENT_OR_BLANK;
      }
      if (line.startsWith("//", index)) {
        return isGeneratedCodeMarker(line.substring(index))
          ? PreambleLineType.GENERATED_MARKER
          : PreambleLineType.COMMENT_OR_BLANK;
      }
      return line.startsWith("/*", index) ? null : PreambleLineType.CODE;
    }

    private static boolean isGeneratedCodeMarker(String line) {
      return line.length() >= GENERATED_CODE_PREFIX.length() + GENERATED_CODE_SUFFIX.length()
        && line.startsWith(GENERATED_CODE_PREFIX)
        && line.endsWith(GENERATED_CODE_SUFFIX);
    }

    private int skipBlockComment(String line, int index) {
      var commentEnd = line.indexOf("*/", index);
      if (commentEnd < 0) {
        return line.length();
      }
      inBlockComment = false;
      return commentEnd + 2;
    }

    private static int skipWhitespace(String line, int index) {
      while (index < line.length() && Character.isWhitespace(line.charAt(index))) {
        index++;
      }
      return index;
    }
  }
}
