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
package io.trino.sql.planner;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.slice.Slices;
import io.trino.Session;
import io.trino.metadata.ResolvedFunction;
import io.trino.metadata.TestingFunctionResolution;
import io.trino.spi.expression.ConnectorExpression;
import io.trino.spi.function.OperatorType;
import io.trino.spi.type.Type;
import io.trino.sql.ir.ArithmeticBinaryExpression;
import io.trino.sql.ir.Constant;
import io.trino.sql.ir.Expression;
import io.trino.sql.ir.FunctionCall;
import io.trino.sql.ir.NodeRef;
import io.trino.sql.ir.SubscriptExpression;
import io.trino.sql.ir.SymbolReference;
import io.trino.transaction.TransactionId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.google.common.collect.Iterables.getOnlyElement;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.RowType.field;
import static io.trino.spi.type.RowType.rowType;
import static io.trino.spi.type.TimestampType.TIMESTAMP_MILLIS;
import static io.trino.spi.type.TimestampWithTimeZoneType.TIMESTAMP_TZ_MILLIS;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.spi.type.VarcharType.createVarcharType;
import static io.trino.sql.analyzer.TypeSignatureProvider.fromTypes;
import static io.trino.sql.ir.ArithmeticBinaryExpression.Operator.ADD;
import static io.trino.sql.planner.ConnectorExpressionTranslator.translate;
import static io.trino.sql.planner.PartialTranslator.extractPartialTranslations;
import static io.trino.sql.planner.TestingPlannerContext.PLANNER_CONTEXT;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static org.assertj.core.api.Assertions.assertThat;

public class TestPartialTranslator
{
    private static final TestingFunctionResolution FUNCTIONS = new TestingFunctionResolution();
    private static final ResolvedFunction ADD_INTEGER = FUNCTIONS.resolveOperator(OperatorType.ADD, ImmutableList.of(INTEGER, INTEGER));

    private static final Session TEST_SESSION = testSessionBuilder()
            .setTransactionId(TransactionId.create())
            .build();
    private static final IrTypeAnalyzer TYPE_ANALYZER = new IrTypeAnalyzer(PLANNER_CONTEXT);
    private static final TypeProvider TYPE_PROVIDER = TypeProvider.copyOf(ImmutableMap.<Symbol, Type>builder()
            .put(new Symbol("double_symbol_1"), DOUBLE)
            .put(new Symbol("double_symbol_2"), DOUBLE)
            .put(new Symbol("bigint_symbol_1"), BIGINT)
            .put(new Symbol("timestamp3_symbol_1"), TIMESTAMP_MILLIS)
            .put(new Symbol("row_symbol_1"), rowType(
                    field("int_symbol_1", INTEGER),
                    field("varchar_symbol_1", createVarcharType(5)),
                    field("timestamptz3_field_1", TIMESTAMP_TZ_MILLIS)))
            .buildOrThrow());

    @Test
    public void testPartialTranslator()
    {
        Expression rowSymbolReference = new SymbolReference("row_symbol_1");
        Expression dereferenceExpression1 = new SubscriptExpression(rowSymbolReference, new Constant(INTEGER, 1L));
        Expression dereferenceExpression2 = new SubscriptExpression(rowSymbolReference, new Constant(INTEGER, 2L));
        Expression stringLiteral = new Constant(VARCHAR, Slices.utf8Slice("abcd"));
        Expression symbolReference1 = new SymbolReference("double_symbol_1");

        assertFullTranslation(symbolReference1);
        assertFullTranslation(dereferenceExpression1);
        assertFullTranslation(stringLiteral);
        assertFullTranslation(new ArithmeticBinaryExpression(ADD_INTEGER, ADD, symbolReference1, dereferenceExpression1));

        Expression functionCallExpression = new FunctionCall(
                PLANNER_CONTEXT.getMetadata().resolveBuiltinFunction("concat", fromTypes(VARCHAR, VARCHAR)),
                ImmutableList.of(stringLiteral, dereferenceExpression2));
        assertFullTranslation(functionCallExpression);
    }

    private void assertFullTranslation(Expression expression)
    {
        Map<NodeRef<Expression>, ConnectorExpression> translation = extractPartialTranslations(expression, TEST_SESSION, TYPE_ANALYZER, TYPE_PROVIDER);
        assertThat(getOnlyElement(translation.keySet())).isEqualTo(NodeRef.of(expression));
        assertThat(getOnlyElement(translation.values())).isEqualTo(translate(TEST_SESSION, expression, TYPE_PROVIDER, TYPE_ANALYZER).get());
    }
}
