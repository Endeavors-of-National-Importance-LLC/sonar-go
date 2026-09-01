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
package org.sonar.go.utils;

import java.math.BigInteger;
import java.util.Optional;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.sonar.go.symbols.Symbol;
import org.sonar.plugins.go.api.BinaryExpressionTree;
import org.sonar.plugins.go.api.HasSymbol;
import org.sonar.plugins.go.api.IdentifierTree;
import org.sonar.plugins.go.api.IntegerLiteralTree;
import org.sonar.plugins.go.api.ParenthesizedExpressionTree;
import org.sonar.plugins.go.api.StringLiteralTree;
import org.sonar.plugins.go.api.Tree;

public class ConstantResolution {

  public static final String PLACEHOLDER = "_?_";
  private static final int MAX_IDENTIFIER_RESOLUTION = 20;
  /**
   * The longest string a concatenation may produce. Resolving a self-referential one, e.g.
   * {@code s = s + "" + s + "" + s + ""}, grows exponentially with {@link #MAX_IDENTIFIER_RESOLUTION} - up to lengths
   * no {@link String} can hold - so a concatenation longer than this is reported as unresolved. A single literal is
   * returned whatever its length: the file it comes from already bounds it.
   */
  private static final int MAX_RESOLVED_LENGTH = 8192;
  /**
   * The steps a single resolution may take, one per node it visits. {@link #MAX_IDENTIFIER_RESOLUTION} bounds how deep
   * it goes and {@link #MAX_RESOLVED_LENGTH} how long the string it builds gets, but neither bounds the tree it walks:
   * every level that resolves a self-reference more than once multiplies the nodes to visit, so their number grows
   * exponentially even while depth and length stay small. Spending the budget stops the resolution, keeping it bounded
   * in time as well as in memory.
   */
  private static final int MAX_RESOLUTION_STEPS = 10_000;

  private ConstantResolution() {
    // Utility class
  }

  /**
   * Try to resolve the tree as a String constant.
   * For any tree that cannot be resolved to a constant, null is returned.
   */
  @CheckForNull
  public static String resolveAsStringConstant(@Nullable Tree tree) {
    var resolvedConstant = resolveAsPartialStringConstant(tree);
    if (resolvedConstant.contains(PLACEHOLDER)) {
      return null;
    }
    return resolvedConstant;
  }

  /**
   * @return true when the tree represents a static string constant, false otherwise.
   */
  public static boolean isConstantString(Tree tree) {
    return !resolveAsPartialStringConstant(tree).contains(PLACEHOLDER);
  }

  /**
   * Try to resolve this tree as a string constant.
   * If there are nodes that cannot be resolved, they are replaced by {@link #PLACEHOLDER}.
   * This way, the result can still be used to detect certain patterns involving string concatenation, e.g.
   * {@code "/tmp/" + fileName} will be resolved to {@code "/tmp/_?_"}, and will contain information that the string describes a temporary file.
   * To avoid infinite recursion, we call the method {@link #resolveAsPartialStringConstant(Tree, int, Steps)} where the
   * second parameter is a counter set initially to {@link #MAX_IDENTIFIER_RESOLUTION}. Everytime it resolve an identifier through
   * {@link #resolveIdentifierAsStringConstant(IdentifierTree, int, Steps)}, the counter is decremented. If it reaches 0, it stops the resolution and
   * return {@link #PLACEHOLDER}.
   */
  @Nonnull
  public static String resolveAsPartialStringConstant(@Nullable Tree tree) {
    return resolveAsPartialStringConstant(tree, MAX_IDENTIFIER_RESOLUTION, new Steps());
  }

  @Nonnull
  private static String resolveAsPartialStringConstant(@Nullable Tree tree, int remainingIdentifierResolution, Steps steps) {
    if (tree == null || remainingIdentifierResolution == 0 || steps.isExhausted()) {
      return PLACEHOLDER;
    }
    steps.spendOne();
    if (tree instanceof StringLiteralTree stringLiteral) {
      return stringLiteral.content();
    } else if (tree instanceof BinaryExpressionTree binaryExpressionTree && binaryExpressionTree.operator() == BinaryExpressionTree.Operator.PLUS) {
      return concatenate(
        resolveAsPartialStringConstant(binaryExpressionTree.leftOperand(), remainingIdentifierResolution, steps),
        resolveAsPartialStringConstant(binaryExpressionTree.rightOperand(), remainingIdentifierResolution, steps));
    } else if (tree instanceof ParenthesizedExpressionTree parenthesizedExpression) {
      return resolveAsPartialStringConstant(parenthesizedExpression.expression(), remainingIdentifierResolution, steps);
    } else if (tree instanceof IdentifierTree identifier) {
      return resolveIdentifierAsStringConstant(identifier, remainingIdentifierResolution, steps);
    }
    return PLACEHOLDER;
  }

  /**
   * Concatenates two resolved operands, giving up as soon as the result outgrows {@link #MAX_RESOLVED_LENGTH}. Giving up
   * here rather than on the final result keeps every intermediate string bounded, so that a concatenation whose
   * resolution grows exponentially cannot exhaust the heap or overflow the length of a {@link String}.
   */
  private static String concatenate(String left, String right) {
    if (left.length() + right.length() > MAX_RESOLVED_LENGTH) {
      return PLACEHOLDER;
    }
    return left + right;
  }

  private static String resolveIdentifierAsStringConstant(IdentifierTree identifier, int remainingIdentifierResolution, Steps steps) {
    Symbol symbol = identifier.symbol();
    if (symbol == null) {
      return PLACEHOLDER;
    }
    return Optional.ofNullable(symbol.getSafeValue())
      .map(value -> resolveAsPartialStringConstant(value, remainingIdentifierResolution - 1, steps))
      .orElse(PLACEHOLDER);
  }

  /**
   * Try to evaluate the tree as an integer constant, {@code null} when it is not one. The recursion is bounded the same
   * way {@link #resolveAsPartialStringConstant(Tree)} is: a self-referential value, e.g. {@code n = n + 1}, would
   * otherwise recurse until the stack is exhausted, and {@code n = n + n} would branch at every level on top of that.
   */
  @CheckForNull
  public static BigInteger evaluateArithmeticExpression(Tree tree) {
    return evaluateArithmeticExpression(tree, MAX_IDENTIFIER_RESOLUTION, new Steps());
  }

  @CheckForNull
  private static BigInteger evaluateArithmeticExpression(@Nullable Tree tree, int remainingIdentifierResolution, Steps steps) {
    if (tree == null || remainingIdentifierResolution == 0 || steps.isExhausted()) {
      return null;
    }
    steps.spendOne();
    if (tree instanceof IntegerLiteralTree integerLiteral) {
      return integerLiteral.getIntegerValue();
    } else if (tree instanceof ParenthesizedExpressionTree parenthesizedExpression) {
      return evaluateArithmeticExpression(parenthesizedExpression.expression(), remainingIdentifierResolution, steps);
    } else if (tree instanceof HasSymbol hasSymbol && hasSymbol.symbol() != null) {
      var safeValue = hasSymbol.symbol().getSafeValue();
      if (safeValue != null) {
        return evaluateArithmeticExpression(safeValue, remainingIdentifierResolution - 1, steps);
      }
    } else if (tree instanceof BinaryExpressionTree binaryExpression) {
      // Go parser already produces the tree with the correct order of operations, so we can simply evaluate operands recursively
      var left = evaluateArithmeticExpression(binaryExpression.leftOperand(), remainingIdentifierResolution, steps);
      var right = evaluateArithmeticExpression(binaryExpression.rightOperand(), remainingIdentifierResolution, steps);
      if (left == null || right == null) {
        return null;
      }
      return switch (binaryExpression.operator()) {
        case PLUS -> left.add(right);
        case MINUS -> left.subtract(right);
        case TIMES -> left.multiply(right);
        case DIVIDED_BY -> right.equals(BigInteger.ZERO) ? null : left.divide(right);
        default -> null;
      };
    }
    return null;
  }

  /** The work one resolution has left, shared by every step it takes. */
  private static final class Steps {
    private int remaining = MAX_RESOLUTION_STEPS;

    private boolean isExhausted() {
      return remaining <= 0;
    }

    private void spendOne() {
      remaining--;
    }
  }
}
