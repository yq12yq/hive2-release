/*
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements.  See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership.  The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
 */

package org.apache.hadoop.hive.ql.parse;

import org.antlr.runtime.tree.Tree;
import org.apache.hadoop.hive.ql.QueryState;
import org.apache.hadoop.hive.ql.metadata.InvalidTableException;
import org.apache.hadoop.hive.ql.metadata.Table;
import org.apache.hadoop.hive.ql.parse.repl.dump.TableExport;

/**
 * ExportSemanticAnalyzer.
 *
 */
public class ExportSemanticAnalyzer extends BaseSemanticAnalyzer {

  ExportSemanticAnalyzer(QueryState queryState) throws SemanticException {
    super(queryState);
  }

  @Override
  public void analyzeInternal(ASTNode ast) throws SemanticException {
    Tree tableTree = ast.getChild(0);
    Tree toTree = ast.getChild(1);

    ReplicationSpec replicationSpec;
    if (ast.getChildCount() > 2) {
      replicationSpec = new ReplicationSpec((ASTNode) ast.getChild(2));
    } else {
      replicationSpec = new ReplicationSpec();
    }

    // initialize source table/partition
    TableSpec ts;

    try {
      ts = new TableSpec(db, conf, (ASTNode) tableTree, false, true);
    } catch (SemanticException sme){
      if ((replicationSpec.isInReplicationScope()) &&
            ((sme.getCause() instanceof InvalidTableException)
            || (sme instanceof Table.ValidationFailureSemanticException)
            )
          ){
        // If we're in replication scope, it's possible that we're running the export long after
        // the table was dropped, so the table not existing currently or being a different kind of
        // table is not an error - it simply means we should no-op, and let a future export
        // capture the appropriate state
        ts = null;
      } else {
        throw sme;
      }
    }

    // initialize export path
    String tmpPath = stripQuotes(toTree.getText());
    // All parsing is done, we're now good to start the export process.
    TableExport.Paths exportPaths =
        new TableExport.Paths(tmpPath, conf);
    TableExport.AuthEntities authEntities =
        new TableExport(exportPaths, ts, replicationSpec, db, null, conf).write();
    inputs.addAll(authEntities.inputs);
    outputs.addAll(authEntities.outputs);
  }
}
