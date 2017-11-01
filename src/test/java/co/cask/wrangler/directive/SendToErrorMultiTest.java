package co.cask.wrangler.directive;

import co.cask.wrangler.api.RecipePipeline;
import co.cask.wrangler.api.Row;
import co.cask.wrangler.test.TestingRig;
import co.cask.wrangler.test.api.TestRecipe;
import co.cask.wrangler.test.api.TestRows;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Tests {@link SendToErrorMulti}
 */
public class SendToErrorMultiTest {
  @Test
  public void testBasicReverse() throws Exception {
    TestRecipe recipe = new TestRecipe();
    recipe.add("parse-as-csv :body ',';");
    recipe.add("drop :body");
    recipe.add("set-headers :encounter,:age,:first,:last;");
    // TODO: Add the send-to-error-multi directive with two conditions:
    // 1. checks that the encounter is empty
    // 2. checks that age is less than or equal to 0
    recipe.add("send-to-error-multi \'string:isEmpty(encounter),age<=0\'");

    TestRows rows = new TestRows();
    rows.add(new Row("body", "en1,0,john,doe"));
    rows.add(new Row("body", "en2,20,samuel,jackson"));
    rows.add(new Row("body", ",30,sam,foe"));

    RecipePipeline pipeline = TestingRig.pipeline(SendToErrorMulti.class, recipe);
    List<Row> actual = pipeline.execute(rows.toList());
    List errors = pipeline.errors();

    Assert.assertEquals(1, actual.size());
    Assert.assertEquals(2, errors.size());
  }
}
