/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.sql.ir;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.collect.ImmutableList;

import java.util.List;

import static java.util.Objects.requireNonNull;

@JsonSerialize
public record ComparisonExpression(Operator operator, Expression left, Expression right)
        implements Expression
{
    public enum Operator
    {
        EQUAL("="),
        NOT_EQUAL("<>"),
        LESS_THAN("<"),
        LESS_THAN_OR_EQUAL("<="),
        GREATER_THAN(">"),
        GREATER_THAN_OR_EQUAL(">="),
        IS_DISTINCT_FROM("IS DISTINCT FROM");

        private final String value;

        Operator(String value)
        {
            this.value = value;
        }

        public String getValue()
        {
            return value;
        }

        public Operator flip()
        {
            return switch (this) {
                case EQUAL -> EQUAL;
                case NOT_EQUAL -> NOT_EQUAL;
                case LESS_THAN -> GREATER_THAN;
                case LESS_THAN_OR_EQUAL -> GREATER_THAN_OR_EQUAL;
                case GREATER_THAN -> LESS_THAN;
                case GREATER_THAN_OR_EQUAL -> LESS_THAN_OR_EQUAL;
                case IS_DISTINCT_FROM -> IS_DISTINCT_FROM;
            };
        }

        public Operator negate()
        {
            switch (this) {
                case EQUAL:
                    return NOT_EQUAL;
                case NOT_EQUAL:
                    return EQUAL;
                case LESS_THAN:
                    return GREATER_THAN_OR_EQUAL;
                case LESS_THAN_OR_EQUAL:
                    return GREATER_THAN;
                case GREATER_THAN:
                    return LESS_THAN_OR_EQUAL;
                case GREATER_THAN_OR_EQUAL:
                    return LESS_THAN;
                case IS_DISTINCT_FROM:
                    // Cannot negate
                    break;
            }
            throw new IllegalArgumentException("Unsupported comparison: " + this);
        }
    }

    public ComparisonExpression
    {
        requireNonNull(operator, "operator is null");
        requireNonNull(left, "left is null");
        requireNonNull(right, "right is null");
    }

    @Deprecated
    public Operator getOperator()
    {
        return operator;
    }

    @Deprecated
    public Expression getLeft()
    {
        return left;
    }

    @Deprecated
    public Expression getRight()
    {
        return right;
    }

    @Override
    public <R, C> R accept(IrVisitor<R, C> visitor, C context)
    {
        return visitor.visitComparisonExpression(this, context);
    }

    @Override
    public List<? extends Expression> getChildren()
    {
        return ImmutableList.of(left, right);
    }

    @Override
    public String toString()
    {
        return "%s(%s, %s)".formatted(
                switch (operator) {
                    case IS_DISTINCT_FROM -> "DistinctFrom";
                    default -> operator.getValue();
                },
                left,
                right);
    }
}
