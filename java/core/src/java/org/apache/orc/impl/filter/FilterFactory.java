/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.orc.impl.filter;

import org.apache.hadoop.hive.ql.io.sarg.ExpressionTree;
import org.apache.hadoop.hive.ql.io.sarg.PredicateLeaf;
import org.apache.hadoop.hive.ql.io.sarg.SearchArgument;
import org.apache.orc.OrcFile;
import org.apache.orc.Reader;
import org.apache.orc.TypeDescription;
import org.apache.orc.filter.BatchFilter;
import org.apache.orc.impl.filter.leaf.LeafFilterFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FilterFactory {
  private static final Logger LOG = LoggerFactory.getLogger(FilterFactory.class);

  /**
   * Create a BatchFilter. This considers both the input filter and the SearchArgument filter. If
   * both are available then they are compounded by AND.
   *
   * @param opts       for reading the file
   * @param readSchema that should be used
   * @param version    provides the ORC file version
   * @param normalize  identifies if the SArg should be normalized or not
   * @return BatchFilter that represents the SearchArgument or null
   */
  public static BatchFilter createBatchFilter(Reader.Options opts,
                                              TypeDescription readSchema,
                                              OrcFile.Version version,
                                              boolean normalize) {
    List<BatchFilter> filters = new ArrayList<>(2);

    // 1. Process SArgFilter
    if (opts.isAllowSARGToFilter() && opts.getSearchArgument() != null) {
      SearchArgument sArg = opts.getSearchArgument();
      Set<String> colNames = new HashSet<>();
      try {
        ExpressionTree exprTree = normalize ? sArg.getExpression() : sArg.getCompactExpression();
        LOG.debug("normalize={}, using expressionTree={}", normalize, exprTree);
        filters.add(BatchFilterFactory.create(createSArgFilter(exprTree,
                                                               colNames,
                                                               sArg.getLeaves(),
                                                               readSchema,
                                                               version),
                                              colNames.toArray(new String[0])));
      } catch (UnSupportedSArgException e) {
        LOG.warn("SArg: {} is not supported\n{}", sArg, e.getMessage());
      }
    }

    // 2. Process input filter
    if (opts.getFilterCallback() != null) {
      filters.add(BatchFilterFactory.create(opts.getFilterCallback(),
                                            opts.getPreFilterColumnNames()));
    }
    return BatchFilterFactory.create(filters);
  }

  public static VectorFilter createSArgFilter(ExpressionTree expr,
                                              Set<String> colIds,
                                              List<PredicateLeaf> leaves,
                                              TypeDescription readSchema,
                                              OrcFile.Version version)
    throws UnSupportedSArgException {
    VectorFilter result;
    switch (expr.getOperator()) {
      case OR:
        VectorFilter[] orFilters = new VectorFilter[expr.getChildren().size()];
        for (int i = 0; i < expr.getChildren().size(); i++) {
          orFilters[i] = createSArgFilter(expr.getChildren().get(i),
                                          colIds,
                                          leaves,
                                          readSchema,
                                          version);
        }
        result = new OrFilter(orFilters);
        break;
      case AND:
        VectorFilter[] andFilters = new VectorFilter[expr.getChildren().size()];
        for (int i = 0; i < expr.getChildren().size(); i++) {
          andFilters[i] = createSArgFilter(expr.getChildren().get(i),
                                           colIds,
                                           leaves,
                                           readSchema,
                                           version);
        }
        result = new AndFilter(andFilters);
        break;
      case NOT:
        // Not is expected to be pushed down that it only happens on leaf filters
        ExpressionTree leaf = expr.getChildren().get(0);
        assert leaf.getOperator() == ExpressionTree.Operator.LEAF;
        result = LeafFilterFactory.createLeafVectorFilter(leaves.get(leaf.getLeaf()),
                                                             colIds,
                                                             readSchema,
                                                             version,
            true);
        break;
      case LEAF:
        result = LeafFilterFactory.createLeafVectorFilter(leaves.get(expr.getLeaf()),
                                                          colIds,
                                                          readSchema,
                                                          version,
            false);
        break;
      default:
        throw new UnSupportedSArgException(String.format("SArg expression: %s is not supported",
                                                         expr));
    }
    return result;
  }

  public static class UnSupportedSArgException extends Exception {

    public UnSupportedSArgException(String message) {
      super(message);
    }
  }
}
