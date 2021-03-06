/*
 * Copyright 2017-2019 CodingApi .
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.codingapi.txlcn.client.core.txc.resource;

import com.codingapi.txlcn.client.bean.DTXLocal;
import com.codingapi.txlcn.client.core.txc.resource.def.SqlExecuteInterceptor;
import com.codingapi.txlcn.client.core.txc.resource.def.TxcService;
import com.codingapi.txlcn.client.core.txc.resource.def.bean.*;
import com.codingapi.txlcn.client.core.txc.resource.util.SqlUtils;
import com.codingapi.txlcn.commons.exception.TxcLogicException;
import com.codingapi.txlcn.jdbcproxy.p6spy.common.StatementInformation;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.SelectExpressionItem;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.update.Update;
import org.apache.commons.dbutils.DbUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Description: 拦截必要的SQL, 植入TXC逻辑
 * <p>
 * Date: 2018/12/13
 *
 * @author ujued
 */
@Slf4j
public class TxcSqlExecuteInterceptor implements SqlExecuteInterceptor {

    private final TableStructAnalyser tableStructAnalyser;

    private final TxcService txcService;

    public TxcSqlExecuteInterceptor(TableStructAnalyser tableStructAnalyser, TxcService txcService) {
        this.tableStructAnalyser = tableStructAnalyser;
        this.txcService = txcService;
    }

    @Override
    public void preUpdate(Update update) throws SQLException {
        // 获取线程传递参数
        String groupId = DTXLocal.cur().getGroupId();
        String unitId = DTXLocal.cur().getUnitId();
        RollbackInfo rollbackInfo = (RollbackInfo) DTXLocal.cur().getAttachment();
        Connection connection = (Connection) DTXLocal.cur().getResource();


        // Update相关数据准备
        List<String> columns = new ArrayList<>(update.getColumns().size());
        List<String> primaryKeys = new ArrayList<>(3);
        List<String> tables = new ArrayList<>(update.getTables().size());
        update.getColumns().forEach(column -> {
            column.setTable(update.getTables().get(0));
            columns.add(column.getFullyQualifiedName());
        });
        for (Table table : update.getTables()) {
            tables.add(table.getName());
            TableStruct tableStruct = tableStructAnalyser.analyse(connection, table.getName());
            tableStruct.getPrimaryKeys().forEach(key -> primaryKeys.add(table.getName() + "." + key));
        }

        // 前置准备
        try {
            txcService.resolveUpdateImage(new UpdateImageParams()
                    .setGroupId(groupId)
                    .setUnitId(unitId)
                    .setRollbackInfo(rollbackInfo)
                    .setColumns(columns)
                    .setPrimaryKeys(primaryKeys)
                    .setTables(tables)
                    .setWhereSql(update.getWhere() == null ? "1=1" : update.getWhere().toString()));
        } catch (TxcLogicException e) {
            throw new SQLException(e.getMessage());
        }
    }

    @Override
    public void preDelete(Delete delete) throws SQLException {
        log.debug("do pre delete: {}", delete);

        // 获取线程传递参数
        RollbackInfo rollbackInfo = (RollbackInfo) DTXLocal.cur().getAttachment();
        String groupId = DTXLocal.cur().getGroupId();
        String unitId = DTXLocal.cur().getUnitId();
        Connection connection = (Connection) DTXLocal.cur().getResource();

        // 获取Sql Table
        if (delete.getTables().size() == 0) {
            delete.setTables(Collections.singletonList(delete.getTable()));
        }

        // Delete Sql 数据
        List<String> tables = new ArrayList<>(delete.getTables().size());
        List<String> primaryKeys = new ArrayList<>(3);
        List<String> columns = new ArrayList<>();

        for (Table table : delete.getTables()) {
            TableStruct tableStruct = tableStructAnalyser.analyse(connection, table.getName());
            tableStruct.getColumns().forEach((k, v) -> {
                columns.add(tableStruct.getTableName() + SqlUtils.DOT + k);
            });
            tableStruct.getPrimaryKeys().forEach(primaryKey -> {
                primaryKeys.add(tableStruct.getTableName() + SqlUtils.DOT + primaryKey);
            });
            tables.add(tableStruct.getTableName());
        }

        // 前置准备
        try {
            txcService.resolveDeleteImage(new DeleteImageParams()
                    .setGroupId(groupId)
                    .setUnitId(unitId)
                    .setRollbackInfo(rollbackInfo)
                    .setSqlWhere(delete.getWhere().toString())
                    .setColumns(columns)
                    .setPrimaryKeys(primaryKeys)
                    .setTables(tables));
        } catch (TxcLogicException e) {
            throw new SQLException(e.getMessage());
        }

    }

    @Override
    public void preInsert(Insert insert) {
    }

    @Override
    public void postInsert(StatementInformation statementInformation) throws SQLException {
        String groupId = DTXLocal.cur().getGroupId();
        String unitId = DTXLocal.cur().getUnitId();
        Connection connection = (Connection) DTXLocal.cur().getResource();
        Insert insert = (Insert) statementInformation.getAttachment();
        TableStruct tableStruct = tableStructAnalyser.analyse(connection, insert.getTable().getName());

        // 解决主键
        PrimaryKeyListVisitor primaryKeyListVisitor = new PrimaryKeyListVisitor(insert.getTable(),
                insert.getColumns(), tableStruct.getFullyQualifiedPrimaryKeys());
        insert.getItemsList().accept(primaryKeyListVisitor);

        // 自增主键
        ResultSet rs = statementInformation.getStatement().getGeneratedKeys();
        StringBuilder rollbackSql = new StringBuilder(SqlUtils.DELETE)
                .append(SqlUtils.FROM)
                .append(tableStruct.getTableName())
                .append(SqlUtils.WHERE);
        List<Object> params = new ArrayList<>();

        for (int i = 0; rs.next(); i++) {
            Map<String, Object> pks = primaryKeyListVisitor.getPrimaryKeyValuesList().get(i);
            for (String key : tableStruct.getFullyQualifiedPrimaryKeys()) {
                rollbackSql.append(key).append("=? and ");
                if (pks.containsKey(key)) {
                    params.add(pks.get(key));
                } else {
                    params.add(rs.getObject(1));
                }
            }
            SqlUtils.cutSuffix(SqlUtils.AND, rollbackSql);
            rollbackSql.append(SqlUtils.OR);
        }
        DbUtils.close(rs);
        SqlUtils.cutSuffix(SqlUtils.OR, rollbackSql);

        if (params.size() == 0) {
            log.warn("nothing to insert");
            return;
        }

        // 设置Rollback SQL
        RollbackInfo rollbackInfo = (RollbackInfo) DTXLocal.cur().getAttachment();
        try {
            txcService.resolveInsertImage(new InsertImageParams(groupId, unitId, rollbackSql.toString(), params, rollbackInfo));
        } catch (TxcLogicException e) {
            throw new SQLException(e);
        }
    }

    @Override
    public void preSelect(LockableSelect lockableSelect) throws SQLException {
        // 忽略无锁的查询
        if (!lockableSelect.shouldLock()) {
            return;
        }

        // 不支持非PlainSelect
        if (!(lockableSelect.statement().getSelectBody() instanceof PlainSelect)) {
            throw new SQLException("non support this query when use control lock.");
        }

        PlainSelect plainSelect = (PlainSelect) lockableSelect.statement().getSelectBody();

        // 不支持复杂的FromItem
        if (!(plainSelect.getFromItem() instanceof Table)) {
            throw new SQLException("non support this query when use control lock.");
        }


        // 构造查询需要判断锁行的SQL
        List<String> primaryKeys = new ArrayList<>();
        Table leftTable = (Table) plainSelect.getFromItem();
        List<SelectItem> selectItems = new ArrayList<>();
        Connection connection = (Connection) DTXLocal.cur().getResource();

        TableStruct leftTableStruct = tableStructAnalyser.analyse(connection, leftTable.getName());
        leftTableStruct.getPrimaryKeys().forEach(primaryKey -> {
            Column column = new Column(leftTable, primaryKey);
            selectItems.add(new SelectExpressionItem(column));
            primaryKeys.add(column.getFullyQualifiedName());
        });

        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                if (join.isSimple()) {
                    TableStruct rightTableStruct = tableStructAnalyser.analyse(connection, join.getRightItem().toString());
                    rightTableStruct.getPrimaryKeys().forEach(primaryKey -> {
                        Column column = new Column((Table) join.getRightItem(), primaryKey);
                        selectItems.add(new SelectExpressionItem(column));
                        primaryKeys.add(column.getFullyQualifiedName());
                    });
                }
            }
        }
        plainSelect.setSelectItems(selectItems);

        // 尝试锁定
        log.info("lock select sql: {}", plainSelect);
        String groupId = DTXLocal.cur().getGroupId();
        String unitId = DTXLocal.cur().getUnitId();
        RollbackInfo rollbackInfo = (RollbackInfo) DTXLocal.cur().getAttachment();

        SelectImageParams selectImageParams = new SelectImageParams();
        selectImageParams.setGroupId(groupId);
        selectImageParams.setUnitId(unitId);
        selectImageParams.setPrimaryKeys(primaryKeys);
        selectImageParams.setRollbackInfo(rollbackInfo);
        selectImageParams.setSql(plainSelect.toString());

        try {
            txcService.lockSelect(selectImageParams, lockableSelect.isxLock());
        } catch (TxcLogicException e) {
            throw new SQLException(e.getMessage());
        }
    }

}
