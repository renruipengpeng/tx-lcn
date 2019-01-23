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
package com.codingapi.txlcn.tc.core.txc.resource;

import com.codingapi.txlcn.commons.exception.TCGlobalContextException;
import com.codingapi.txlcn.commons.exception.TxcLogicException;
import com.codingapi.txlcn.commons.lock.DTXLocks;
import com.codingapi.txlcn.spi.message.exception.RpcException;
import com.codingapi.txlcn.tc.core.DTXLocalContext;
import com.codingapi.txlcn.tc.core.txc.resource.def.TxcService;
import com.codingapi.txlcn.tc.core.txc.resource.def.TxcSqlExecutor;
import com.codingapi.txlcn.tc.core.txc.resource.def.bean.*;
import com.codingapi.txlcn.tc.core.txc.resource.undo.TableRecord;
import com.codingapi.txlcn.tc.core.txc.resource.undo.TableRecordList;
import com.codingapi.txlcn.tc.core.txc.resource.undo.UndoLogAnalyser;
import com.codingapi.txlcn.tc.core.txc.resource.util.SqlUtils;
import com.codingapi.txlcn.tc.corelog.txc.TxcLogHelper;
import com.codingapi.txlcn.tc.message.ReliableMessenger;
import com.codingapi.txlcn.tc.support.context.TCGlobalContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.dbutils.DbUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/**
 * Description:
 * Date: 2018/12/17
 *
 * @author ujued
 */

@Slf4j
@Component
public class TxcServiceImpl implements TxcService {

    private final TxcSqlExecutor txcSqlExecutor;

    private final TxcLogHelper txcLogHelper;

    private final ReliableMessenger reliableMessenger;

    private final TCGlobalContext globalContext;

    @Autowired
    public TxcServiceImpl(TxcSqlExecutor txcSqlExecutor, TxcLogHelper txcLogHelper,
                          ReliableMessenger reliableMessenger, TCGlobalContext globalContext) {
        this.txcSqlExecutor = txcSqlExecutor;
        this.txcLogHelper = txcLogHelper;
        this.reliableMessenger = reliableMessenger;
        this.globalContext = globalContext;
    }

    /**
     * lock data line
     *
     * @param groupId   groupId
     * @param unitId    unitId
     * @param lockIdSet lockIdSet
     * @param isXLock   isXLock
     * @throws TxcLogicException 业务异常
     */
    private void lockDataLine(String groupId, String unitId, Set<String> lockIdSet, boolean isXLock) throws TxcLogicException {
        try {
            if (!reliableMessenger.acquireLocks(lockIdSet, isXLock ? DTXLocks.X_LOCK : DTXLocks.S_LOCK)) {
                throw new TxcLogicException("resource is locked! place try again later.");
            }
            globalContext.addTxcLockId(groupId, unitId, lockIdSet);
        } catch (RpcException e) {
            throw new TxcLogicException("can't contact to any TM for lock info. default error.");
        }
    }

    /**
     * save sql undo log
     *
     * @param groupId    groupId
     * @param unitId     unitId
     * @param sqlType    sqlType
     * @param recordList recordList
     * @throws TxcLogicException 业务异常
     */
    private void saveUndoLog(String groupId, String unitId, int sqlType, TableRecordList recordList) throws TxcLogicException {
        UndoLogDO undoLogDO = new UndoLogDO();
        undoLogDO.setRollbackInfo(SqlUtils.objectToBlob(recordList));
        undoLogDO.setUnitId(unitId);
        undoLogDO.setGroupId(groupId);
        undoLogDO.setSqlType(sqlType);
        try {
            txcLogHelper.saveUndoLog(undoLogDO);
        } catch (SQLException e) {
            throw new TxcLogicException(e);
        }
    }

    /**
     * Hex String
     *
     * @param content origin string
     * @return hex
     */
    private String hex(String content) {
        return DigestUtils.md5DigestAsHex(content.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void lockSelect(SelectImageParams selectImageParams, boolean isxLock) throws TxcLogicException {
        Connection connection = (Connection) DTXLocalContext.cur().getResource();
        try {
            List<ModifiedRecord> modifiedRecords = txcSqlExecutor.selectSqlPreviousPrimaryKeys(connection, selectImageParams);
            Set<String> lockIdSet = new HashSet<>();
            for (ModifiedRecord modifiedRecord : modifiedRecords) {
                for (Map.Entry<String, FieldCluster> entry : modifiedRecord.getFieldClusters().entrySet()) {
                    FieldCluster v = entry.getValue();
                    // key value MD5 HEX to store
                    lockIdSet.add(hex(v.getPrimaryKeys().toString()));
                }
            }
            lockDataLine(DTXLocalContext.cur().getGroupId(), DTXLocalContext.cur().getUnitId(), lockIdSet, isxLock);
        } catch (SQLException e) {
            throw new TxcLogicException(e);
        }
    }

    @Override
    public void resolveUpdateImage(UpdateImageParams updateImageParams) throws TxcLogicException {
        // 前置镜像数据集
        List<ModifiedRecord> modifiedRecords;
        Connection connection = (Connection) DTXLocalContext.cur().getResource();
        try {
            modifiedRecords = txcSqlExecutor.updateSqlPreviousData(connection, updateImageParams);
        } catch (SQLException e) {
            throw new TxcLogicException(e);
        }

        TableRecordList tableRecords = new TableRecordList();
        Set<String> lockIdSet = new HashSet<>();

        // Build reverse sql
        for (ModifiedRecord modifiedRecord : modifiedRecords) {
            for (Map.Entry<String, FieldCluster> entry : modifiedRecord.getFieldClusters().entrySet()) {
                TableRecord tableRecord = new TableRecord();
                tableRecord.setTableName(entry.getKey());
                tableRecord.setFieldCluster(entry.getValue());

                tableRecords.getTableRecords().add(tableRecord);

                lockIdSet.add(hex(tableRecord.getFieldCluster().getPrimaryKeys().toString()));
            }
        }

        String groupId = DTXLocalContext.cur().getGroupId();
        String unitId = DTXLocalContext.cur().getUnitId();

        // lock
        lockDataLine(groupId, unitId, lockIdSet, true);

        // save to h2db
        saveUndoLog(groupId, unitId, SqlUtils.SQL_TYPE_UPDATE, tableRecords);
    }

    @Override
    public void resolveDeleteImage(DeleteImageParams deleteImageParams) throws TxcLogicException {
        // 前置数据
        List<ModifiedRecord> modifiedRecords;
        Connection connection = (Connection) DTXLocalContext.cur().getResource();
        try {
            modifiedRecords = txcSqlExecutor.deleteSqlPreviousData(connection, deleteImageParams);
        } catch (SQLException e) {
            throw new TxcLogicException(e);
        }

        TableRecordList tableRecords = new TableRecordList();
        Set<String> lockIdSet = new HashSet<>();

        // rollback sql
        for (ModifiedRecord modifiedRecord : modifiedRecords) {
            for (Map.Entry<String, FieldCluster> entry : modifiedRecord.getFieldClusters().entrySet()) {
                TableRecord tableRecord = new TableRecord();
                tableRecord.setTableName(entry.getKey());
                tableRecord.setFieldCluster(entry.getValue());

                lockIdSet.add(hex(tableRecord.getFieldCluster().getPrimaryKeys().toString()));
                tableRecords.getTableRecords().add(tableRecord);
            }
        }

        String groupId = DTXLocalContext.cur().getGroupId();
        String unitId = DTXLocalContext.cur().getUnitId();

        // Lock Resource
        lockDataLine(groupId, unitId, lockIdSet, true);

        // save to db
        saveUndoLog(groupId, unitId, SqlUtils.SQL_TYPE_DELETE, tableRecords);
    }

    @Override
    public void resolveInsertImage(InsertImageParams insertImageParams) throws TxcLogicException {
        List<FieldValue> primaryKeys = new ArrayList<>();
        FieldCluster fieldCluster = new FieldCluster();
        fieldCluster.setPrimaryKeys(primaryKeys);
        try {
            for (int i = 0; i < insertImageParams.getPrimaryKeyValuesList().size(); i++) {
                insertImageParams.getResultSet().next();
                Map<String, Object> pks = insertImageParams.getPrimaryKeyValuesList().get(i);
                for (String key : insertImageParams.getFullyQualifiedPrimaryKeys()) {
                    FieldValue fieldValue = new FieldValue();
                    fieldValue.setFieldName(key);
                    if (pks.containsKey(key)) {
                        fieldValue.setValue(pks.get(key));
                    } else {
                        fieldValue.setValue(insertImageParams.getResultSet().getObject(1));
                    }
                    primaryKeys.add(fieldValue);
                }
            }
        } catch (SQLException e) {
            throw new TxcLogicException(e);
        } finally {
            try {
                DbUtils.close(insertImageParams.getResultSet());
            } catch (SQLException ignored) {
            }
        }

        // save to db
        TableRecordList tableRecords = new TableRecordList();
        tableRecords.getTableRecords().add(new TableRecord(insertImageParams.getTableName(), fieldCluster));
        saveUndoLog(insertImageParams.getGroupId(), insertImageParams.getUnitId(), SqlUtils.SQL_TYPE_INSERT, tableRecords);
    }

    @Override
    public void cleanTxc(String groupId, String unitId) throws TxcLogicException {
        // 清理事务单元相关锁
        try {
            reliableMessenger.releaseLocks(globalContext.findTxcLockSet(groupId, unitId));
        } catch (RpcException e) {
            throw new TxcLogicException(e);
        } catch (TCGlobalContextException e) {
            // ignored, if non lock to release.
        }

        // 清理事务单元相关undo_log
        try {
            txcLogHelper.deleteUndoLog(groupId, unitId);
        } catch (SQLException e) {
            throw new TxcLogicException(e);
        }
    }

    @Override
    public void undo(String groupId, String unitId) throws TxcLogicException {
        try {
            List<StatementInfo> statementInfoList = new ArrayList<>();
            List<UndoLogDO> undoLogDOList = txcLogHelper.getUndoLogByGroupAndUnitId(groupId, unitId);

            for (UndoLogDO undoLogDO : undoLogDOList) {
                TableRecordList tableRecords = SqlUtils.blobToObject(undoLogDO.getRollbackInfo(), TableRecordList.class);
                switch (undoLogDO.getSqlType()) {
                    case SqlUtils.SQL_TYPE_UPDATE:
                        tableRecords.getTableRecords().forEach(tableRecord -> statementInfoList.add(UndoLogAnalyser.update(tableRecord)));
                        break;
                    case SqlUtils.SQL_TYPE_DELETE:
                        tableRecords.getTableRecords().forEach(tableRecord -> statementInfoList.add(UndoLogAnalyser.delete(tableRecord)));
                        break;
                    case SqlUtils.SQL_TYPE_INSERT:
                        tableRecords.getTableRecords().forEach(tableRecord -> statementInfoList.add(UndoLogAnalyser.insert(tableRecord)));
                        break;
                    default:
                        break;
                }
            }
            txcSqlExecutor.applyUndoLog(statementInfoList);
        } catch (SQLException e) {
            throw new TxcLogicException(e);
        }
    }
}