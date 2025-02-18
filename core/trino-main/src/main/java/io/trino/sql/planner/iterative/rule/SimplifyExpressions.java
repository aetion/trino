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
package io.trino.sql.planner.iterative.rule;

import com.google.common.collect.ImmutableSet;
import io.trino.Session;
import io.trino.spi.type.Type;
import io.trino.sql.PlannerContext;
import io.trino.sql.ir.Constant;
import io.trino.sql.ir.Expression;
import io.trino.sql.ir.NodeRef;
import io.trino.sql.ir.SymbolReference;
import io.trino.sql.planner.IrExpressionInterpreter;
import io.trino.sql.planner.IrTypeAnalyzer;
import io.trino.sql.planner.NoOpSymbolResolver;
import io.trino.sql.planner.SymbolAllocator;
import io.trino.sql.planner.iterative.Rule;

import java.util.Map;
import java.util.Set;

import static io.trino.sql.planner.iterative.rule.ExtractCommonPredicatesExpressionRewriter.extractCommonPredicates;
import static io.trino.sql.planner.iterative.rule.NormalizeOrExpressionRewriter.normalizeOrExpression;
import static io.trino.sql.planner.iterative.rule.PushDownNegationsExpressionRewriter.pushDownNegations;
import static java.util.Objects.requireNonNull;

public class SimplifyExpressions
        extends ExpressionRewriteRuleSet
{
    public static Expression rewrite(Expression expression, Session session, SymbolAllocator symbolAllocator, PlannerContext plannerContext, IrTypeAnalyzer typeAnalyzer)
    {
        requireNonNull(plannerContext, "plannerContext is null");
        requireNonNull(typeAnalyzer, "typeAnalyzer is null");
        if (expression instanceof SymbolReference) {
            return expression;
        }
        Map<NodeRef<Expression>, Type> expressionTypes = typeAnalyzer.getTypes(symbolAllocator.getTypes(), expression);
        expression = pushDownNegations(expression, expressionTypes);
        expression = extractCommonPredicates(expression);
        expression = normalizeOrExpression(expression);
        expressionTypes = typeAnalyzer.getTypes(symbolAllocator.getTypes(), expression);
        IrExpressionInterpreter interpreter = new IrExpressionInterpreter(expression, plannerContext, session, expressionTypes);
        Object optimized = interpreter.optimize(NoOpSymbolResolver.INSTANCE);

        return optimized instanceof Expression optimizedExpression ?
                optimizedExpression :
                new Constant(expressionTypes.get(NodeRef.of(expression)), optimized);
    }

    public SimplifyExpressions(PlannerContext plannerContext, IrTypeAnalyzer typeAnalyzer)
    {
        super(createRewrite(plannerContext, typeAnalyzer));
    }

    @Override
    public Set<Rule<?>> rules()
    {
        return ImmutableSet.of(
                projectExpressionRewrite(),
                filterExpressionRewrite(),
                joinExpressionRewrite(),
                valuesExpressionRewrite(),
                patternRecognitionExpressionRewrite()); // ApplyNode and AggregationNode are not supported, because ExpressionInterpreter doesn't support them
    }

    private static ExpressionRewriter createRewrite(PlannerContext plannerContext, IrTypeAnalyzer typeAnalyzer)
    {
        requireNonNull(plannerContext, "plannerContext is null");
        requireNonNull(typeAnalyzer, "typeAnalyzer is null");

        return (expression, context) -> rewrite(expression, context.getSession(), context.getSymbolAllocator(), plannerContext, typeAnalyzer);
    }
}
