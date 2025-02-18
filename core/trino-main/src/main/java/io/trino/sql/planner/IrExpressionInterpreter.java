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
import com.google.common.primitives.Primitives;
import io.trino.Session;
import io.trino.metadata.Metadata;
import io.trino.metadata.ResolvedFunction;
import io.trino.operator.scalar.ArraySubscriptOperator;
import io.trino.spi.TrinoException;
import io.trino.spi.block.SqlRow;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.function.CatalogSchemaFunctionName;
import io.trino.spi.function.FunctionNullability;
import io.trino.spi.function.InvocationConvention;
import io.trino.spi.function.OperatorType;
import io.trino.spi.type.ArrayType;
import io.trino.spi.type.MapType;
import io.trino.spi.type.RowType;
import io.trino.spi.type.Type;
import io.trino.sql.InterpretedFunctionInvoker;
import io.trino.sql.PlannerContext;
import io.trino.sql.ir.ArithmeticBinaryExpression;
import io.trino.sql.ir.ArithmeticNegation;
import io.trino.sql.ir.BetweenPredicate;
import io.trino.sql.ir.BindExpression;
import io.trino.sql.ir.Cast;
import io.trino.sql.ir.CoalesceExpression;
import io.trino.sql.ir.ComparisonExpression;
import io.trino.sql.ir.ComparisonExpression.Operator;
import io.trino.sql.ir.Constant;
import io.trino.sql.ir.Expression;
import io.trino.sql.ir.FunctionCall;
import io.trino.sql.ir.InPredicate;
import io.trino.sql.ir.IrVisitor;
import io.trino.sql.ir.IsNullPredicate;
import io.trino.sql.ir.LambdaExpression;
import io.trino.sql.ir.LogicalExpression;
import io.trino.sql.ir.NodeRef;
import io.trino.sql.ir.NotExpression;
import io.trino.sql.ir.NullIfExpression;
import io.trino.sql.ir.Row;
import io.trino.sql.ir.SearchedCaseExpression;
import io.trino.sql.ir.SimpleCaseExpression;
import io.trino.sql.ir.SubscriptExpression;
import io.trino.sql.ir.SymbolReference;
import io.trino.sql.ir.WhenClause;
import io.trino.type.FunctionType;
import io.trino.type.TypeCoercion;
import io.trino.util.FastutilSetHelper;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Predicates.instanceOf;
import static com.google.common.base.Throwables.throwIfInstanceOf;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getOnlyElement;
import static io.trino.metadata.GlobalFunctionCatalog.builtinFunctionName;
import static io.trino.spi.StandardErrorCode.INVALID_FUNCTION_ARGUMENT;
import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.trino.spi.block.RowValueBuilder.buildRowValue;
import static io.trino.spi.function.InvocationConvention.InvocationArgumentConvention.NEVER_NULL;
import static io.trino.spi.function.InvocationConvention.InvocationReturnConvention.FAIL_ON_NULL;
import static io.trino.spi.function.InvocationConvention.InvocationReturnConvention.NULLABLE_RETURN;
import static io.trino.spi.function.InvocationConvention.simpleConvention;
import static io.trino.spi.function.OperatorType.HASH_CODE;
import static io.trino.spi.type.TypeUtils.readNativeValue;
import static io.trino.spi.type.TypeUtils.writeNativeValue;
import static io.trino.sql.DynamicFilters.isDynamicFilter;
import static io.trino.sql.gen.VarArgsToMapAdapterGenerator.generateVarArgsToMapAdapter;
import static io.trino.sql.planner.DeterminismEvaluator.isDeterministic;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;

public class IrExpressionInterpreter
{
    private static final CatalogSchemaFunctionName FAIL_NAME = builtinFunctionName("fail");

    private final Expression expression;
    private final PlannerContext plannerContext;
    private final Metadata metadata;
    private final ConnectorSession connectorSession;
    private final Map<NodeRef<Expression>, Type> expressionTypes;
    private final InterpretedFunctionInvoker functionInvoker;
    private final TypeCoercion typeCoercion;

    private final IdentityHashMap<List<Expression>, Set<?>> inListCache = new IdentityHashMap<>();

    public IrExpressionInterpreter(Expression expression, PlannerContext plannerContext, Session session, Map<NodeRef<Expression>, Type> expressionTypes)
    {
        this.expression = requireNonNull(expression, "expression is null");
        this.plannerContext = requireNonNull(plannerContext, "plannerContext is null");
        this.metadata = plannerContext.getMetadata();
        this.connectorSession = session.toConnectorSession();
        this.expressionTypes = ImmutableMap.copyOf(requireNonNull(expressionTypes, "expressionTypes is null"));
        verify(expressionTypes.containsKey(NodeRef.of(expression)));
        this.functionInvoker = new InterpretedFunctionInvoker(plannerContext.getFunctionManager());
        this.typeCoercion = new TypeCoercion(plannerContext.getTypeManager()::getType);
    }

    public static Object evaluateConstantExpression(Expression expression, PlannerContext plannerContext, Session session)
    {
        Map<NodeRef<Expression>, Type> types = new IrTypeAnalyzer(plannerContext).getTypes(TypeProvider.empty(), expression);
        return new IrExpressionInterpreter(expression, plannerContext, session, types).evaluate();
    }

    public Object evaluate()
    {
        Object result = new Visitor(false).processWithExceptionHandling(expression, null);
        verify(!(result instanceof Expression), "Expression interpreter returned an unresolved expression");
        return result;
    }

    public Object evaluate(SymbolResolver inputs)
    {
        Object result = new Visitor(false).processWithExceptionHandling(expression, inputs);
        verify(!(result instanceof Expression), "Expression interpreter returned an unresolved expression");
        return result;
    }

    public Object optimize(SymbolResolver inputs)
    {
        return new Visitor(true).processWithExceptionHandling(expression, inputs);
    }

    private class Visitor
            extends IrVisitor<Object, Object>
    {
        private final boolean optimize;

        private Visitor(boolean optimize)
        {
            this.optimize = optimize;
        }

        private Object processWithExceptionHandling(Expression expression, Object context)
        {
            if (expression == null) {
                return null;
            }

            try {
                return process(expression, context);
            }
            catch (TrinoException e) {
                if (optimize) {
                    // Certain operations like 0 / 0 or likeExpression may throw exceptions.
                    // When optimizing, do not throw the exception, but delay it until the expression is actually executed.
                    // This is to take advantage of the possibility that some other optimization removes the erroneous
                    // expression from the plan.
                    return expression;
                }
                // Do not suppress exceptions during expression execution.
                throw e;
            }
        }

        @Override
        protected Object visitSymbolReference(SymbolReference node, Object context)
        {
            return ((SymbolResolver) context).getValue(Symbol.from(node));
        }

        @Override
        protected Object visitConstant(Constant node, Object context)
        {
            return node.getValue();
        }

        @Override
        protected Object visitIsNullPredicate(IsNullPredicate node, Object context)
        {
            Object value = processWithExceptionHandling(node.getValue(), context);

            if (value instanceof Expression) {
                return new IsNullPredicate(toExpression(value, type(node.getValue())));
            }

            return value == null;
        }

        @Override
        protected Object visitSearchedCaseExpression(SearchedCaseExpression node, Object context)
        {
            Object newDefault = null;
            boolean foundNewDefault = false;

            List<WhenClause> whenClauses = new ArrayList<>();
            for (WhenClause whenClause : node.getWhenClauses()) {
                Object whenOperand = processWithExceptionHandling(whenClause.getOperand(), context);

                if (whenOperand instanceof Expression) {
                    // cannot fully evaluate, add updated whenClause
                    whenClauses.add(new WhenClause(
                            toExpression(whenOperand, type(whenClause.getOperand())),
                            toExpression(processWithExceptionHandling(whenClause.getResult(), context), type(whenClause.getResult()))));
                }
                else if (Boolean.TRUE.equals(whenOperand)) {
                    // condition is true, use this as default
                    foundNewDefault = true;
                    newDefault = processWithExceptionHandling(whenClause.getResult(), context);
                    break;
                }
            }

            Object defaultResult;
            if (foundNewDefault) {
                defaultResult = newDefault;
            }
            else {
                defaultResult = processWithExceptionHandling(node.getDefaultValue().orElse(null), context);
            }

            if (whenClauses.isEmpty()) {
                return defaultResult;
            }

            Expression defaultExpression = (defaultResult == null) ? null : toExpression(defaultResult, type(node));
            return new SearchedCaseExpression(whenClauses, Optional.ofNullable(defaultExpression));
        }

        @Override
        protected Object visitSimpleCaseExpression(SimpleCaseExpression node, Object context)
        {
            Object operand = processWithExceptionHandling(node.getOperand(), context);
            Type operandType = type(node.getOperand());

            // if operand is null, return defaultValue
            if (operand == null) {
                return processWithExceptionHandling(node.getDefaultValue().orElse(null), context);
            }

            Object newDefault = null;
            boolean foundNewDefault = false;

            List<WhenClause> whenClauses = new ArrayList<>();
            for (WhenClause whenClause : node.getWhenClauses()) {
                Object whenOperand = processWithExceptionHandling(whenClause.getOperand(), context);

                if (whenOperand instanceof Expression || operand instanceof Expression) {
                    // cannot fully evaluate, add updated whenClause
                    whenClauses.add(new WhenClause(
                            toExpression(whenOperand, type(whenClause.getOperand())),
                            toExpression(processWithExceptionHandling(whenClause.getResult(), context), type(whenClause.getResult()))));
                }
                else if (whenOperand != null && isEqual(operand, operandType, whenOperand, type(whenClause.getOperand()))) {
                    // condition is true, use this as default
                    foundNewDefault = true;
                    newDefault = processWithExceptionHandling(whenClause.getResult(), context);
                    break;
                }
            }

            Object defaultResult;
            if (foundNewDefault) {
                defaultResult = newDefault;
            }
            else {
                defaultResult = processWithExceptionHandling(node.getDefaultValue().orElse(null), context);
            }

            if (whenClauses.isEmpty()) {
                return defaultResult;
            }

            Expression defaultExpression = (defaultResult == null) ? null : toExpression(defaultResult, type(node));
            return new SimpleCaseExpression(toExpression(operand, type(node.getOperand())), whenClauses, Optional.ofNullable(defaultExpression));
        }

        private boolean isEqual(Object operand1, Type type1, Object operand2, Type type2)
        {
            return Boolean.TRUE.equals(invokeOperator(OperatorType.EQUAL, ImmutableList.of(type1, type2), ImmutableList.of(operand1, operand2)));
        }

        private Type type(Expression expression)
        {
            Type type = expressionTypes.get(NodeRef.of(expression));
            checkState(type != null, "Type not found for expression: %s", expression);
            return type;
        }

        @Override
        protected Object visitCoalesceExpression(CoalesceExpression node, Object context)
        {
            List<Object> newOperands = processOperands(node, context);
            if (newOperands.isEmpty()) {
                return null;
            }
            if (newOperands.size() == 1) {
                return getOnlyElement(newOperands);
            }
            return new CoalesceExpression(newOperands.stream()
                    .map(value -> toExpression(value, type(node)))
                    .collect(toImmutableList()));
        }

        private List<Object> processOperands(CoalesceExpression node, Object context)
        {
            List<Object> newOperands = new ArrayList<>();
            Set<Expression> uniqueNewOperands = new HashSet<>();
            for (Expression operand : node.getOperands()) {
                Object value = processWithExceptionHandling(operand, context);
                if (value instanceof CoalesceExpression) {
                    // The nested CoalesceExpression was recursively processed. It does not contain null.
                    for (Expression nestedOperand : ((CoalesceExpression) value).getOperands()) {
                        // Skip duplicates unless they are non-deterministic.
                        if (!isDeterministic(nestedOperand) || uniqueNewOperands.add(nestedOperand)) {
                            newOperands.add(nestedOperand);
                        }
                        // This operand can be evaluated to a non-null value. Remaining operands can be skipped.
                        if (nestedOperand instanceof Constant constant && constant.getValue() != null) {
                            return newOperands;
                        }
                    }
                }
                else if (value instanceof Expression expression) {
                    // Skip duplicates unless they are non-deterministic.
                    if (!isDeterministic(expression) || uniqueNewOperands.add(expression)) {
                        newOperands.add(expression);
                    }
                }
                else if (value != null) {
                    // This operand can be evaluated to a non-null value. Remaining operands can be skipped.
                    newOperands.add(value);
                    return newOperands;
                }
            }
            return newOperands;
        }

        @Override
        protected Object visitInPredicate(InPredicate node, Object context)
        {
            Object value = processWithExceptionHandling(node.getValue(), context);

            List<Expression> valueList = node.getValueList();
            // `NULL IN ()` would be false, but InListExpression cannot be empty by construction
            if (value == null) {
                return null;
            }

            Type type = type(node.getValue());
            if (!(value instanceof Expression) &&
                    !(type instanceof ArrayType) && // equals/hashcode doesn't work for complex types that may contain nulls
                    !(type instanceof MapType) &&
                    !(type instanceof RowType)) {
                Set<?> set = inListCache.get(valueList);

                // We use the presence of the node in the map to indicate that we've already done
                // the analysis below. If the value is null, it means that we can't apply the HashSet
                // optimization
                if (!inListCache.containsKey(valueList)) {
                    boolean nonNullConstants = valueList.stream().allMatch(Constant.class::isInstance) &&
                            valueList.stream()
                                    .map(Constant.class::cast)
                                    .map(Constant::getValue)
                                    .noneMatch(Objects::isNull);
                    if (nonNullConstants) {
                        Set<Object> objectSet = valueList.stream().map(expression -> processWithExceptionHandling(expression, context)).collect(Collectors.toSet());
                        set = FastutilSetHelper.toFastutilHashSet(
                                objectSet,
                                type,
                                plannerContext.getFunctionManager().getScalarFunctionImplementation(metadata.resolveOperator(HASH_CODE, ImmutableList.of(type)), simpleConvention(FAIL_ON_NULL, NEVER_NULL)).getMethodHandle(),
                                plannerContext.getFunctionManager().getScalarFunctionImplementation(metadata.resolveOperator(OperatorType.EQUAL, ImmutableList.of(type, type)), simpleConvention(NULLABLE_RETURN, NEVER_NULL, NEVER_NULL)).getMethodHandle());
                    }
                    inListCache.put(valueList, set);
                }

                if (set != null) {
                    return set.contains(value);
                }
            }

            boolean hasUnresolvedValue = value instanceof Expression;
            boolean hasNullValue = false;
            boolean found = false;
            List<Object> values = new ArrayList<>(valueList.size());
            List<Type> types = new ArrayList<>(valueList.size());

            ResolvedFunction equalsOperator = metadata.resolveOperator(OperatorType.EQUAL, types(node.getValue(), node.getValue()));
            for (Expression expression : valueList) {
                if (value instanceof Expression && expression instanceof Constant) {
                    // skip interpreting of literal IN term since it cannot be compared
                    // with unresolved "value" and it cannot be simplified further
                    values.add(expression);
                    types.add(type(expression));
                    continue;
                }

                // Use process() instead of processWithExceptionHandling() for processing in-list items.
                // Do not handle exceptions thrown while processing a single in-list expression,
                // but fail the whole in-predicate evaluation.
                // According to in-predicate semantics, all in-list items must be successfully evaluated
                // before a check for the match is performed.
                Object inValue = process(expression, context);
                if (value instanceof Expression || inValue instanceof Expression) {
                    hasUnresolvedValue = true;
                    values.add(inValue);
                    types.add(type(expression));
                    continue;
                }

                if (inValue == null) {
                    hasNullValue = true;
                }
                else {
                    Boolean result = (Boolean) functionInvoker.invoke(equalsOperator, connectorSession, ImmutableList.of(value, inValue));
                    if (result == null) {
                        hasNullValue = true;
                    }
                    else if (!found && result) {
                        // in does not short-circuit so we must evaluate all value in the list
                        found = true;
                    }
                }
            }
            if (found) {
                return true;
            }

            if (hasUnresolvedValue) {
                List<Expression> expressionValues = toExpressions(values, types);
                List<Expression> simplifiedExpressionValues = Stream.concat(
                                expressionValues.stream()
                                        .filter(expression -> isDeterministic(expression))
                                        .distinct(),
                                expressionValues.stream()
                                        .filter(expression -> !isDeterministic(expression)))
                        .collect(toImmutableList());

                if (simplifiedExpressionValues.size() == 1) {
                    return new ComparisonExpression(Operator.EQUAL, toExpression(value, type), simplifiedExpressionValues.get(0));
                }

                return new InPredicate(toExpression(value, type), simplifiedExpressionValues);
            }
            if (hasNullValue) {
                return null;
            }
            return false;
        }

        @Override
        protected Object visitArithmeticNegation(ArithmeticNegation node, Object context)
        {
            Object value = processWithExceptionHandling(node.getValue(), context);
            if (value == null) {
                return null;
            }
            if (value instanceof Expression) {
                Expression valueExpression = toExpression(value, type(node.getValue()));
                if (valueExpression instanceof ArithmeticNegation argument) {
                    return argument.getValue();
                }
                return new ArithmeticNegation(valueExpression);
            }

            ResolvedFunction resolvedOperator = metadata.resolveOperator(OperatorType.NEGATION, types(node.getValue()));
            InvocationConvention invocationConvention = new InvocationConvention(ImmutableList.of(NEVER_NULL), FAIL_ON_NULL, true, false);
            MethodHandle handle = plannerContext.getFunctionManager().getScalarFunctionImplementation(resolvedOperator, invocationConvention).getMethodHandle();

            if (handle.type().parameterCount() > 0 && handle.type().parameterType(0) == ConnectorSession.class) {
                handle = handle.bindTo(connectorSession);
            }
            try {
                return handle.invokeWithArguments(value);
            }
            catch (Throwable throwable) {
                throwIfInstanceOf(throwable, RuntimeException.class);
                throwIfInstanceOf(throwable, Error.class);
                throw new RuntimeException(throwable.getMessage(), throwable);
            }
        }

        @Override
        protected Object visitArithmeticBinary(ArithmeticBinaryExpression node, Object context)
        {
            Object left = processWithExceptionHandling(node.getLeft(), context);
            if (left == null) {
                return null;
            }
            Object right = processWithExceptionHandling(node.getRight(), context);
            if (right == null) {
                return null;
            }

            if (hasUnresolvedValue(left, right)) {
                return new ArithmeticBinaryExpression(node.getFunction(), node.getOperator(), toExpression(left, type(node.getLeft())), toExpression(right, type(node.getRight())));
            }

            return functionInvoker.invoke(node.getFunction(), connectorSession, ImmutableList.of(left, right));
        }

        @Override
        protected Object visitComparisonExpression(ComparisonExpression node, Object context)
        {
            Operator operator = node.getOperator();
            Expression left = node.getLeft();
            Expression right = node.getRight();

            if (operator == Operator.IS_DISTINCT_FROM) {
                return processIsDistinctFrom(context, left, right);
            }
            // Execution engine does not have not equal and greater than operators, so interpret with
            // equal or less than, but do not flip operator in result, as many optimizers depend on
            // operators not flipping
            if (node.getOperator() == Operator.NOT_EQUAL) {
                Object result = visitComparisonExpression(flipComparison(node), context);
                if (result == null) {
                    return null;
                }
                if (result instanceof ComparisonExpression) {
                    return flipComparison((ComparisonExpression) result);
                }
                return !(Boolean) result;
            }
            if (node.getOperator() == Operator.GREATER_THAN || node.getOperator() == Operator.GREATER_THAN_OR_EQUAL) {
                Object result = visitComparisonExpression(flipComparison(node), context);
                if (result instanceof ComparisonExpression) {
                    return flipComparison((ComparisonExpression) result);
                }
                return result;
            }

            return processComparisonExpression(context, operator, left, right);
        }

        private Object processIsDistinctFrom(Object context, Expression leftExpression, Expression rightExpression)
        {
            Object left = processWithExceptionHandling(leftExpression, context);
            Object right = processWithExceptionHandling(rightExpression, context);

            if (left == null && right instanceof Expression) {
                return new NotExpression(new IsNullPredicate(((Expression) right)));
            }

            if (right == null && left instanceof Expression) {
                return new NotExpression(new IsNullPredicate(((Expression) left)));
            }

            if (left instanceof Expression || right instanceof Expression) {
                return new ComparisonExpression(Operator.IS_DISTINCT_FROM, toExpression(left, type(leftExpression)), toExpression(right, type(rightExpression)));
            }

            return invokeOperator(OperatorType.valueOf(Operator.IS_DISTINCT_FROM.name()), types(leftExpression, rightExpression), Arrays.asList(left, right));
        }

        private Object processComparisonExpression(Object context, Operator operator, Expression leftExpression, Expression rightExpression)
        {
            Object left = processWithExceptionHandling(leftExpression, context);
            if (left == null) {
                return null;
            }

            Object right = processWithExceptionHandling(rightExpression, context);
            if (right == null) {
                return null;
            }

            if (left instanceof Expression || right instanceof Expression) {
                return new ComparisonExpression(operator, toExpression(left, type(leftExpression)), toExpression(right, type(rightExpression)));
            }

            return invokeOperator(OperatorType.valueOf(operator.name()), types(leftExpression, rightExpression), ImmutableList.of(left, right));
        }

        // TODO define method contract or split into separate methods, as flip(EQUAL) is a negation, while flip(LESS_THAN) is just flipping sides
        private ComparisonExpression flipComparison(ComparisonExpression comparisonExpression)
        {
            return switch (comparisonExpression.getOperator()) {
                case EQUAL -> new ComparisonExpression(Operator.NOT_EQUAL, comparisonExpression.getLeft(), comparisonExpression.getRight());
                case NOT_EQUAL -> new ComparisonExpression(Operator.EQUAL, comparisonExpression.getLeft(), comparisonExpression.getRight());
                case LESS_THAN -> new ComparisonExpression(Operator.GREATER_THAN, comparisonExpression.getRight(), comparisonExpression.getLeft());
                case LESS_THAN_OR_EQUAL -> new ComparisonExpression(Operator.GREATER_THAN_OR_EQUAL, comparisonExpression.getRight(), comparisonExpression.getLeft());
                case GREATER_THAN -> new ComparisonExpression(Operator.LESS_THAN, comparisonExpression.getRight(), comparisonExpression.getLeft());
                case GREATER_THAN_OR_EQUAL -> new ComparisonExpression(Operator.LESS_THAN_OR_EQUAL, comparisonExpression.getRight(), comparisonExpression.getLeft());
                default -> throw new IllegalStateException("Unexpected value: " + comparisonExpression.getOperator());
            };
        }

        @Override
        protected Object visitBetweenPredicate(BetweenPredicate node, Object context)
        {
            Object value = processWithExceptionHandling(node.getValue(), context);
            if (value == null) {
                return null;
            }
            Object min = processWithExceptionHandling(node.getMin(), context);
            Object max = processWithExceptionHandling(node.getMax(), context);

            if (value instanceof Expression || min instanceof Expression || max instanceof Expression) {
                return new BetweenPredicate(
                        toExpression(value, type(node.getValue())),
                        toExpression(min, type(node.getMin())),
                        toExpression(max, type(node.getMax())));
            }

            Boolean greaterOrEqualToMin = null;
            if (min != null) {
                greaterOrEqualToMin = (Boolean) invokeOperator(OperatorType.LESS_THAN_OR_EQUAL, types(node.getMin(), node.getValue()), ImmutableList.of(min, value));
            }
            Boolean lessThanOrEqualToMax = null;
            if (max != null) {
                lessThanOrEqualToMax = (Boolean) invokeOperator(OperatorType.LESS_THAN_OR_EQUAL, types(node.getValue(), node.getMax()), ImmutableList.of(value, max));
            }

            if (greaterOrEqualToMin == null) {
                return Objects.equals(lessThanOrEqualToMax, Boolean.FALSE) ? false : null;
            }
            if (lessThanOrEqualToMax == null) {
                return Objects.equals(greaterOrEqualToMin, Boolean.FALSE) ? false : null;
            }
            return greaterOrEqualToMin && lessThanOrEqualToMax;
        }

        @Override
        protected Object visitNullIfExpression(NullIfExpression node, Object context)
        {
            Object first = processWithExceptionHandling(node.getFirst(), context);
            if (first == null) {
                return null;
            }
            Object second = processWithExceptionHandling(node.getSecond(), context);
            if (second == null) {
                return first;
            }

            Type firstType = type(node.getFirst());
            Type secondType = type(node.getSecond());

            if (hasUnresolvedValue(first, second)) {
                return new NullIfExpression(toExpression(first, firstType), toExpression(second, secondType));
            }

            Type commonType = typeCoercion.getCommonSuperType(firstType, secondType).get();

            ResolvedFunction firstCast = metadata.getCoercion(firstType, commonType);
            ResolvedFunction secondCast = metadata.getCoercion(secondType, commonType);

            // cast(first as <common type>) == cast(second as <common type>)
            boolean equal = Boolean.TRUE.equals(invokeOperator(
                    OperatorType.EQUAL,
                    ImmutableList.of(commonType, commonType),
                    ImmutableList.of(
                            functionInvoker.invoke(firstCast, connectorSession, ImmutableList.of(first)),
                            functionInvoker.invoke(secondCast, connectorSession, ImmutableList.of(second)))));

            if (equal) {
                return null;
            }
            return first;
        }

        @Override
        protected Object visitNotExpression(NotExpression node, Object context)
        {
            Object value = processWithExceptionHandling(node.getValue(), context);
            if (value == null) {
                return null;
            }

            if (value instanceof Expression) {
                return new NotExpression(toExpression(value, type(node.getValue())));
            }

            return !(Boolean) value;
        }

        @Override
        protected Object visitLogicalExpression(LogicalExpression node, Object context)
        {
            List<Object> terms = new ArrayList<>();
            List<Type> types = new ArrayList<>();

            for (Expression term : node.getTerms()) {
                Object processed = processWithExceptionHandling(term, context);

                switch (node.getOperator()) {
                    case AND -> {
                        if (Boolean.FALSE.equals(processed)) {
                            return false;
                        }
                        if (!Boolean.TRUE.equals(processed)) {
                            terms.add(processed);
                            types.add(type(term));
                        }
                    }
                    case OR -> {
                        if (Boolean.TRUE.equals(processed)) {
                            return true;
                        }
                        if (!Boolean.FALSE.equals(processed)) {
                            terms.add(processed);
                            types.add(type(term));
                        }
                    }
                }
            }

            if (terms.isEmpty()) {
                return switch (node.getOperator()) {
                    case AND -> true; // terms are true
                    case OR -> false; // all terms are false
                };
            }

            if (terms.size() == 1) {
                return terms.get(0);
            }

            if (terms.stream().allMatch(Objects::isNull)) {
                return null;
            }

            ImmutableList.Builder<Expression> expressions = ImmutableList.builder();
            for (int i = 0; i < terms.size(); i++) {
                expressions.add(toExpression(terms.get(i), types.get(i)));
            }
            return new LogicalExpression(node.getOperator(), expressions.build());
        }

        @Override
        protected Object visitFunctionCall(FunctionCall node, Object context)
        {
            List<Type> argumentTypes = new ArrayList<>();
            List<Object> argumentValues = new ArrayList<>();
            for (Expression expression : node.getArguments()) {
                Object value = processWithExceptionHandling(expression, context);
                Type type = type(expression);
                argumentValues.add(value);
                argumentTypes.add(type);
            }

            ResolvedFunction resolvedFunction = node.getFunction();
            FunctionNullability functionNullability = resolvedFunction.getFunctionNullability();
            for (int i = 0; i < argumentValues.size(); i++) {
                Object value = argumentValues.get(i);
                if (value == null && !functionNullability.isArgumentNullable(i)) {
                    return null;
                }
            }

            // do not optimize non-deterministic functions
            if (optimize && (!resolvedFunction.isDeterministic() ||
                    hasUnresolvedValue(argumentValues) ||
                    isDynamicFilter(node) ||
                    resolvedFunction.getSignature().getName().equals(FAIL_NAME))) {
                return ResolvedFunctionCallBuilder.builder(resolvedFunction)
                        .setArguments(toExpressions(argumentValues, argumentTypes))
                        .build();
            }
            return functionInvoker.invoke(resolvedFunction, connectorSession, argumentValues);
        }

        @Override
        protected Object visitLambdaExpression(LambdaExpression node, Object context)
        {
            if (optimize) {
                // TODO: enable optimization related to lambda expression
                // A mechanism to convert function type back into lambda expression need to exist to enable optimization
                Object value = processWithExceptionHandling(node.getBody(), context);
                Expression optimizedBody;

                // value may be null, converted to an expression by toExpression(value, type)
                if (value instanceof Expression) {
                    optimizedBody = (Expression) value;
                }
                else {
                    Type type = type(node.getBody());
                    optimizedBody = toExpression(value, type);
                }
                return new LambdaExpression(node.getArguments(), optimizedBody);
            }

            Expression body = node.getBody();
            List<String> argumentNames = node.getArguments();
            FunctionType functionType = (FunctionType) expressionTypes.get(NodeRef.<Expression>of(node));
            checkArgument(argumentNames.size() == functionType.getArgumentTypes().size());

            return generateVarArgsToMapAdapter(
                    Primitives.wrap(functionType.getReturnType().getJavaType()),
                    functionType.getArgumentTypes().stream()
                            .map(Type::getJavaType)
                            .map(Primitives::wrap)
                            .collect(toImmutableList()),
                    argumentNames,
                    map -> processWithExceptionHandling(body, new LambdaSymbolResolver(map)));
        }

        @Override
        protected Object visitBindExpression(BindExpression node, Object context)
        {
            List<Object> values = node.getValues().stream()
                    .map(value -> processWithExceptionHandling(value, context))
                    .collect(toList()); // values are nullable
            Object function = processWithExceptionHandling(node.getFunction(), context);

            if (hasUnresolvedValue(values) || hasUnresolvedValue(function)) {
                ImmutableList.Builder<Expression> builder = ImmutableList.builder();
                for (int i = 0; i < values.size(); i++) {
                    builder.add(toExpression(values.get(i), type(node.getValues().get(i))));
                }

                return new BindExpression(
                        builder.build(),
                        toExpression(function, type(node.getFunction())));
            }

            return MethodHandles.insertArguments((MethodHandle) function, 0, values.toArray());
        }

        @Override
        public Object visitCast(Cast node, Object context)
        {
            Object value = processWithExceptionHandling(node.getExpression(), context);
            Type targetType = node.getType();
            Type sourceType = type(node.getExpression());
            if (value instanceof Expression) {
                if (targetType.equals(sourceType)) {
                    return value;
                }

                return new Cast((Expression) value, node.getType(), node.isSafe());
            }

            if (value == null) {
                return null;
            }

            ResolvedFunction operator = metadata.getCoercion(sourceType, targetType);

            try {
                return functionInvoker.invoke(operator, connectorSession, ImmutableList.of(value));
            }
            catch (RuntimeException e) {
                if (node.isSafe()) {
                    return null;
                }
                throw e;
            }
        }

        @Override
        protected Object visitRow(Row node, Object context)
        {
            RowType rowType = (RowType) type(node);
            List<Type> parameterTypes = rowType.getTypeParameters();
            List<Expression> arguments = node.getItems();

            int cardinality = arguments.size();
            List<Object> values = new ArrayList<>(cardinality);
            for (Expression argument : arguments) {
                values.add(processWithExceptionHandling(argument, context));
            }
            if (hasUnresolvedValue(values)) {
                return new Row(toExpressions(values, parameterTypes));
            }
            return buildRowValue(rowType, fields -> {
                for (int i = 0; i < cardinality; ++i) {
                    writeNativeValue(parameterTypes.get(i), fields.get(i), values.get(i));
                }
            });
        }

        @Override
        protected Object visitSubscriptExpression(SubscriptExpression node, Object context)
        {
            Object base = processWithExceptionHandling(node.getBase(), context);
            if (base == null) {
                return null;
            }
            Object index = processWithExceptionHandling(node.getIndex(), context);
            if (index == null) {
                return null;
            }
            if ((index instanceof Long) && isArray(type(node.getBase()))) {
                ArraySubscriptOperator.checkArrayIndex((Long) index);
            }

            if (hasUnresolvedValue(base, index)) {
                return new SubscriptExpression(toExpression(base, type(node.getBase())), toExpression(index, type(node.getIndex())));
            }

            // Subscript on Row hasn't got a dedicated operator. It is interpreted by hand.
            if (base instanceof SqlRow row) {
                int fieldIndex = toIntExact((long) index - 1);
                if (fieldIndex < 0 || fieldIndex >= row.getFieldCount()) {
                    throw new TrinoException(INVALID_FUNCTION_ARGUMENT, "ROW index out of bounds: " + (fieldIndex + 1));
                }
                Type returnType = type(node.getBase()).getTypeParameters().get(fieldIndex);
                return readNativeValue(returnType, row.getRawFieldBlock(fieldIndex), row.getRawIndex());
            }

            // Subscript on Array or Map is interpreted using operator.
            return invokeOperator(OperatorType.SUBSCRIPT, types(node.getBase(), node.getIndex()), ImmutableList.of(base, index));
        }

        @Override
        protected Object visitExpression(Expression node, Object context)
        {
            throw new TrinoException(NOT_SUPPORTED, "not yet implemented: " + node.getClass().getName());
        }

        private List<Type> types(Expression... expressions)
        {
            return Stream.of(expressions)
                    .map(NodeRef::of)
                    .map(expressionTypes::get)
                    .collect(toImmutableList());
        }

        private boolean hasUnresolvedValue(Object... values)
        {
            return hasUnresolvedValue(ImmutableList.copyOf(values));
        }

        private boolean hasUnresolvedValue(List<Object> values)
        {
            return values.stream().anyMatch(instanceOf(Expression.class));
        }

        private Object invokeOperator(OperatorType operatorType, List<? extends Type> argumentTypes, List<Object> argumentValues)
        {
            ResolvedFunction operator = metadata.resolveOperator(operatorType, argumentTypes);
            return functionInvoker.invoke(operator, connectorSession, argumentValues);
        }

        private Expression toExpression(Object base, Type type)
        {
            if (base instanceof Expression expression) {
                return expression;
            }

            return new Constant(type, base);
        }

        private List<Expression> toExpressions(List<Object> values, List<Type> types)
        {
            checkArgument(values.size() == types.size(), "values and types do not have the same size");

            ImmutableList.Builder<Expression> expressions = ImmutableList.builder();
            for (int i = 0; i < values.size(); i++) {
                Object object = values.get(i);
                expressions.add(object instanceof Expression expression ?
                        expression :
                        new Constant(types.get(i), object));
            }
            return expressions.build();
        }
    }

    private static boolean isArray(Type type)
    {
        return type instanceof ArrayType;
    }

    private static class LambdaSymbolResolver
            implements SymbolResolver
    {
        private final Map<String, Object> values;

        public LambdaSymbolResolver(Map<String, Object> values)
        {
            this.values = requireNonNull(values, "values is null");
        }

        @Override
        public Object getValue(Symbol symbol)
        {
            checkState(values.containsKey(symbol.getName()), "values does not contain %s", symbol);
            return values.get(symbol.getName());
        }
    }
}
