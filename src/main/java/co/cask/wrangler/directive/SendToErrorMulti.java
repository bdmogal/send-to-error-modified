package co.cask.wrangler.directive;

import co.cask.cdap.api.annotation.Description;
import co.cask.cdap.api.annotation.Name;
import co.cask.cdap.api.annotation.Plugin;
import co.cask.wrangler.api.Arguments;
import co.cask.wrangler.api.Directive;
import co.cask.wrangler.api.DirectiveExecutionException;
import co.cask.wrangler.api.DirectiveParseException;
import co.cask.wrangler.api.ErrorRowException;
import co.cask.wrangler.api.ExecutorContext;
import co.cask.wrangler.api.Optional;
import co.cask.wrangler.api.Row;
import co.cask.wrangler.api.annotations.Categories;
import co.cask.wrangler.api.parser.Text;
import co.cask.wrangler.api.parser.TokenType;
import co.cask.wrangler.api.parser.UsageDefinition;
import co.cask.wrangler.expression.EL;
import co.cask.wrangler.expression.ELContext;
import co.cask.wrangler.expression.ELException;
import co.cask.wrangler.expression.ELResult;
import com.google.common.base.Joiner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A directive for erroring the record if
 * <p>
 * <p>
 * This step will evaluate the conditions, if the conditions evaluates to
 * true, then the row will be skipped. If the conditions evaluates to
 * false, then the row will be accepted.
 * </p>
 */
// TODO: register this plugin to be of type Directive, using Directive.TYPE
@Plugin(type = Directive.Type)
@Name(SendToErrorMulti.NAME)
@Categories(categories = {"row", "data-quality"})
@Description("Send records that match the given conditions to the error collector.")
public class SendToErrorMulti implements Directive {
  static final String NAME = "send-to-error-multi";
  private Map<String, ConditionInfo> conditions = new HashMap<>();

  private static final class ConditionInfo {
    private final EL compiledCondition;
    private final String metric;

    private ConditionInfo(EL compiledCondition, String metric) {
      this.compiledCondition = compiledCondition;
      this.metric = metric;
    }
  }

  @Override
  public UsageDefinition define() {
    UsageDefinition.Builder builder = UsageDefinition.builder(NAME);
    // TODO: define the conditions and metrics parameters as TokenType.TEXT. Make the metrics parameter optional.
    builder.define("conditions", TokenType.TEXT);
    builder.define("metrics", TokenType.TEXT, Optional.TRUE);
    return builder.build();
  }

  @Override
  public void initialize(Arguments args) throws DirectiveParseException {
    // TODO: read the value of the parameter "conditions" in the variable "conditionsParamValue"
    String conditionsParamValue = ((Text) args.value("conditions")).value();
    String[] conditionsList = conditionsParamValue.split(",");
    String[] metricsList = null;
    // TODO: read the value of the "metrics" parameter in the variable "metricsParamValue" only if the metrics param
    // exists, since it is an optional parameter
    if (args.contains("metrics")) {
      String metricsParamValue = ((Text) args.value("metrics")).value();
      metricsList = metricsParamValue.split(",");
    }
    if (metricsList != null && conditionsList.length != metricsList.length) {
      throw new DirectiveParseException("Number of metrics should be equal to the number of conditions. Conditions = " +
                                          conditionsList.length + "; metrics = " + metricsList.length);
    }
    // Populate the conditions map
    for (int i = 0; i < conditionsList.length; i++) {
      EL el = new EL(new EL.DefaultFunctions());
      try {
        el.compile(conditionsList[i]);
      } catch (ELException e) {
        throw new DirectiveParseException(
          String.format("Invalid conditions '%s'.", conditions)
        );
      }
      String metric = metricsList != null ? metricsList[i] : null;
      conditions.put(conditionsList[i], new ConditionInfo(el, metric));
    }
  }

  @Override
  public void destroy() {
    // no-op
  }

  @Override
  public List<Row> execute(List<Row> rows, ExecutorContext context)
    throws DirectiveExecutionException, ErrorRowException {
    List<Row> results = new ArrayList<>();
    for (Row row : rows) {
      // Move the fields from the row into the context.
      ELContext ctx = new ELContext();
      ctx.set("this", row);
      boolean atLeastOneConditionSatisfied = false;
      List<String> satisfiedConditions = new ArrayList<>();
      // for each row, go through each condition, and book-keep conditions that are not satisfied
      for (Map.Entry<String, ConditionInfo> conditionEntry : conditions.entrySet()) {
        String condition = conditionEntry.getKey();
        ConditionInfo conditionInfo = conditionEntry.getValue();
        EL el = conditionInfo.compiledCondition;
        String metric = conditionInfo.metric;
        for (String var : el.variables()) {
          ctx.set(var, row.getValue(var));
        }

        // Transient variables are added.
        if (context != null) {
          for (String variable : context.getTransientStore().getVariables()) {
            ctx.set(variable, context.getTransientStore().get(variable));
          }
        }

        // Execution of the script / expression based on the row data
        // mapped into context.
        try {
          ELResult result = el.execute(ctx);
          if (result.getBoolean()) {
            if (metric != null && context != null) {
              context.getMetrics().count(metric, 1);
            }
            // TODO: since the result of executing at least one condition is true, you want to send to error
            // so set the atLeastOneConditionSatisfied variable to 1 in such a case
            // also, since we want to capture all the conditions that were met, capture the condition that was satisfied
            // in the satisfiedConditions list
            atLeastOneConditionSatisfied = true;
            satisfiedConditions.add(condition);
          }
        } catch (ELException e) {
          throw new DirectiveExecutionException(e.getMessage());
        }
      }
      if (atLeastOneConditionSatisfied) {
        // TODO: If at least 1 condition is satisfied, we want to send to error
        // Do that by throwing an ErrorRowException. Set the message in the exception to be a comma separated list of
        // all satisfied conditions
        throw new ErrorRowException(Joiner.on(",").join(satisfiedConditions), 1);
      }
      results.add(row);
    }
    return results;
  }
}