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

package org.apache.flink.playgrounds.creditcardtransactions;

import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.functions.ScalarFunction;

import static org.apache.flink.table.api.Expressions.*;

public class EtlApp {

    public static class MaskingFunction extends ScalarFunction {
        public String eval(String s) {
            return "**".concat(s.substring(2));
        }
    }

    private static final double EXCHANGE_RATE = 1337.36;

    public static Table sqlTransform(TableEnvironment tEnv) {
        return tEnv.sqlQuery(
           "select concat('t_', cast(account_id as varchar)) as id, \n" +
            //"select MaskingFunction(CAST(account_id AS varchar)) as id, \n" +
            "usd_amount * " + EXCHANGE_RATE + " as krw_amount, \n" +
            "transaction_time as ts \n" +
            "from transactions \n" +
            "where account_id is not null \n" +
            "and usd_amount is not null \n" +
            "and transaction_time is not null"
        );
    }

    public static Table transform(Table transactions) {
        return transactions.select(
            $("account_id"),
            $("usd_amount"),
            $("transaction_time"))
          .filter(
            and(
              $("account_id").isNotNull(),
              $("usd_amount").isNotNull(),
              $("transaction_time").isNotNull()
            )
          )
          .select(
            concat("t_",$("account_id").cast(DataTypes.STRING())).as("id"),
            //call(MaskingFunction.class, $("account_id").cast(DataTypes.STRING())).as("id"),
            $("usd_amount").times(EXCHANGE_RATE).as("krw_amount"),
            $("transaction_time").as("ts"));
    }

    public static void main(String[] args) throws Exception {
        EnvironmentSettings settings = EnvironmentSettings.inStreamingMode();
        TableEnvironment tEnv = TableEnvironment.create(settings);

        tEnv.executeSql("CREATE TABLE transactions (\n" +
                "    account_id  BIGINT,\n" +
                "    usd_amount      BIGINT,\n" +
                "    transaction_time TIMESTAMP(3),\n" +
                "    WATERMARK FOR transaction_time AS transaction_time - INTERVAL '5' SECOND\n" +
                ") WITH (\n" +
                "    'connector' = 'kafka',\n" +
                "    'topic'     = 'transactions',\n" +
                "    'properties.bootstrap.servers' = 'localhost:9094',\n" +
                "    'scan.startup.mode' = 'earliest-offset',\n" +
                "    'format'    = 'csv'\n" +
                ")");

        tEnv.executeSql("CREATE TABLE filtered_trx (\n" +
                "    id     STRING,\n" +
                "    krw_amount DOUBLE,\n" +
                "    ts     TIMESTAMP(3),\n" +
                "    PRIMARY KEY (id, ts) NOT ENFORCED" +
                ") WITH (\n" +
                "  'connector'  = 'jdbc',\n" +
                "  'url'        = 'jdbc:mysql://localhost:3306/sql-demo',\n" +
                "  'table-name' = 'filtered_trx',\n" +
                "  'driver'     = 'com.mysql.jdbc.Driver',\n" +
                "  'username'   = 'sql-demo',\n" +
                "  'password'   = 'demo-sql'\n" +
                ")");

        tEnv.createTemporarySystemFunction("MaskingFunction", MaskingFunction.class);

        Table transactions = tEnv.from("transactions");
        transform(transactions).executeInsert("filtered_trx");
        //sqlTransform(tEnv).executeInsert("filtered_trx");
    }
}
