/// Copyright 2017 Pinterest Inc.
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
/// http://www.apache.org/licenses/LICENSE-2.0

/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.

//
// @author bol (bol@pinterest.com)
//

package com.pinterest.rocksplicator;

import com.pinterest.rocksdb_admin.thrift.AddDBRequest;
import com.pinterest.rocksdb_admin.thrift.Admin;
import com.pinterest.rocksdb_admin.thrift.AdminErrorCode;
import com.pinterest.rocksdb_admin.thrift.AdminException;
import com.pinterest.rocksdb_admin.thrift.BackupDBRequest;
import com.pinterest.rocksdb_admin.thrift.ChangeDBRoleAndUpstreamRequest;
import com.pinterest.rocksdb_admin.thrift.CheckDBRequest;
import com.pinterest.rocksdb_admin.thrift.CheckDBResponse;
import com.pinterest.rocksdb_admin.thrift.ClearDBRequest;
import com.pinterest.rocksdb_admin.thrift.CloseDBRequest;
import com.pinterest.rocksdb_admin.thrift.GetSequenceNumberRequest;
import com.pinterest.rocksdb_admin.thrift.GetSequenceNumberResponse;
import com.pinterest.rocksdb_admin.thrift.RestoreDBRequest;

import org.apache.helix.model.Message;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransportException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Utils {
  private static final Logger LOG = LoggerFactory.getLogger(Utils.class);

  /**
   * Build a thrift client to local adminPort
   * @param adminPort
   * @return a client object
   * @throws TTransportException
   */
  public static Admin.Client getLocalAdminClient(int adminPort) throws TTransportException {
    return getAdminClient("localhost", adminPort);
  }

  /**
   * Build a thrift client to host:adminPort
   * @param host
   * @param adminPort
   * @return a client object
   * @throws TTransportException
   */
  public static Admin.Client getAdminClient(String host, int adminPort) throws TTransportException {
    TSocket sock = new TSocket(host, adminPort);
    sock.open();
    return new Admin.Client(new TBinaryProtocol(sock));
  }

  /**
   * Convert a partition name into DB name.
   * @param partitionName  e.g. "p2p1_1"
   * @return e.g. "p2p100001"
   */
  public static String getDbName(String partitionName) {
    int lastIdx= partitionName.lastIndexOf('_');
    return String.format("%s%05d", partitionName.substring(0, lastIdx),
        Integer.parseInt(partitionName.substring(lastIdx + 1)));
  }

  /**
   * Clear the content of a DB and leave it as closed.
   * @param dbName
   * @param adminPort
   */
  public static void clearDB(String dbName, int adminPort) {
    try {
      Admin.Client client = getLocalAdminClient(adminPort);
      ClearDBRequest req = new ClearDBRequest(dbName);
      req.setReopen_db(false);
      client.clearDB(req);
    } catch (AdminException e) {
      LOG.error("Failed to destroy DB", e);
    } catch (TTransportException e) {
      LOG.error("Failed to connect to local Admin port", e);
    } catch (TException e) {
      LOG.error("ClearDB() request failed", e);
    }
  }

  /**
   * Close a DB
   * @param dbName
   * @param adminPort
   */
  public static void closeDB(String dbName, int adminPort) {
    try {
      Admin.Client client = getLocalAdminClient(adminPort);
      CloseDBRequest req = new CloseDBRequest(dbName);
      client.closeDB(req);
    } catch (AdminException e) {
      LOG.error(dbName + " doesn't exist", e);
    } catch (TTransportException e) {
      LOG.error("Failed to connect to local Admin port", e);
    } catch (TException e) {
      LOG.error("CloseDB() request failed", e);
    }
  }

  /**
   * Add a DB as a Slave, and set it upstream to be itself. Do nothing if the DB already exists
   * @param dbName
   * @param adminPort
   */
  public static void addDB(String dbName, int adminPort) {
    Admin.Client client = null;
    AddDBRequest req 
    try {
      try {
        client = getLocalAdminClient(adminPort);
        req = new AddDBRequest(dbName, "127.0.0.1");
        client.addDB(req);
      } catch (AdminException e) {
        if (e.errorCode == AdminErrorCode.DB_EXIST) {
          LOG.error(dbName + " already exists");
          return;
        }

        LOG.error("Failed to open " + dbName, e);
        if (e.errorCode == AdminErrorCode.DB_ERROR) {
          LOG.error("Trying to overwrite open " + dbName);
          req.setOverwrite(true);
          client.addDB(req);
        }
      }
    } catch (TTransportException e) {
      LOG.error("Failed to connect to local Admin port", e);
    } catch (TException e) {
      LOG.error("AddDB() request failed", e);
    }
  }

  /**
   * Log transition meesage
   * @param message
   */
  public static void logTransitionMessage(Message message) {
    LOG.error("Switching from " + message.getFromState() + " to " + message.getToState()
        + " for " + message.getPartitionName());
  }

  /**
   * Get the latest sequence number of the local DB
   * @param dbName
   * @return the latest sequence number
   * @throws RuntimeException
   */
  public static long getLocalLatestSequenceNumber(String dbName, int adminPort)
      throws RuntimeException {
    long seqNum = getLatestSequenceNumber(dbName, "localhost", adminPort);
    if (seqNum == -1) {
      throw new RuntimeException("Failed to fetch local sequence number for DB: " + dbName);
    }

    return seqNum;
  }

  /**
   * Get the latest sequence number of the DB on the host
   * @param dbName
   * @return the latest sequence number, -1 if fails to get it
   */
  public static long getLatestSequenceNumber(String dbName, String host, int adminPort) {
    try {
      Admin.Client client = getAdminClient(host, adminPort);

      GetSequenceNumberRequest request = new GetSequenceNumberRequest(dbName);
      GetSequenceNumberResponse response = client.getSequenceNumber(request);
      return response.seq_num;
    } catch (TException e) {
      LOG.error("Failed to get sequence number", e);
      return -1;
    }
  }

  /**
   * Change DB role and upstream on host:adminPort
   * @param host
   * @param adminPort
   * @param dbName
   * @param role
   * @param upstreamIP
   * @param upstreamPort
   * @throws RuntimeException
   */
  public static void changeDBRoleAndUpStream(
      String host, int adminPort, String dbName, String role, String upstreamIP, int upstreamPort)
      throws RuntimeException {
    try {
      Admin.Client client = getAdminClient(host, adminPort);

      ChangeDBRoleAndUpstreamRequest request = new ChangeDBRoleAndUpstreamRequest(dbName, role);
      request.setUpstream_ip(upstreamIP);
      request.setUpstream_port((short)upstreamPort);
      client.changeDBRoleAndUpStream(request);
    } catch (TException e) {
      LOG.error("Failed to changeDBRoleAndUpStream", e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Check the status of a local DB
   * @param dbName
   * @param adminPort
   * @return the DB status
   * @throws RuntimeException
   */
  public static CheckDBResponse checkLocalDB(String dbName, int adminPort) throws RuntimeException {
    try {
      Admin.Client client = getLocalAdminClient(adminPort);

      CheckDBRequest req = new CheckDBRequest(dbName);
      return client.checkDB(req);
    } catch (TException e) {
      LOG.error("Failed to check DB: ", e.toString());
      throw new RuntimeException(e);
    }
  }

  /**
   * Backup the DB on the host
   * @param host
   * @param adminPort
   * @param dbName
   * @param hdfsPath
   * @throws RuntimeException
   */
  public static void backupDB(String host, int adminPort, String dbName, String hdfsPath)
      throws RuntimeException {
    try {
      Admin.Client client = getAdminClient(host, adminPort);

      BackupDBRequest req = new BackupDBRequest(dbName, hdfsPath);
      client.backupDB(req);
    } catch (TException e) {
      LOG.error("Failed to backup DB: ", e.toString());
      throw new RuntimeException(e);
    }
  }

  /**
   * Restore the local DB from HDFS
   * @param adminPort
   * @param dbName
   * @param hdfsPath
   * @throws RuntimeException
   */
  public static void restoreLocalDB(int adminPort, String dbName, String hdfsPath,
                                    String upsreamHost, int upstreamPort)
      throws RuntimeException {
    try {
      Admin.Client client = getLocalAdminClient(adminPort);

      RestoreDBRequest req =
          new RestoreDBRequest(dbName, hdfsPath, upsreamHost, (short)upstreamPort);
      client.restoreDB(req);
    } catch (TException e) {
      LOG.error("Failed to restore DB: ", e.toString());
      throw new RuntimeException(e);
    }
  }

  /**
   * Check if the DB on host:adminPort is Master. If the CheckDBRequest request fails, return false.
   * @param host
   * @param adminPort
   * @param dbName
   * @return
   */
  public static boolean isMasterReplica(String host, int adminPort, String dbName) {
    try {
      Admin.Client client = getAdminClient(host, adminPort);

      CheckDBRequest req = new CheckDBRequest(dbName);
      CheckDBResponse res = client.checkDB(req);
      return res.is_master;
    } catch (TException e) {
      LOG.error("Failed to check DB: ", e.toString());
      return false;
    }
  }
}
