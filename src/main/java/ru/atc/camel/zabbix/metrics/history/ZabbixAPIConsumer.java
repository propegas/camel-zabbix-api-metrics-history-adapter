package ru.atc.camel.zabbix.metrics.history;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.impl.ScheduledPollConsumer;
import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.atc.monitoring.zabbix.api.DefaultZabbixApi;
import ru.atc.monitoring.zabbix.api.Request;
import ru.atc.monitoring.zabbix.api.RequestBuilder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static ru.atc.adapters.message.CamelMessageManager.genAndSendErrorMessage;
import static ru.atc.adapters.message.CamelMessageManager.genHeartbeatMessage;

public class ZabbixAPIConsumer extends ScheduledPollConsumer {

    private static final int SECONDS_IN_HOUR = 3600;
    private static final int CONNECT_TIMEOUT = 20000;
    private static final int SOCKET_TIMEOUT = 360000;
    private static final int MAX_CONN_PER_ROUTE = 40;
    private static final int MAX_CONN_TOTAL = 40;
    private static final int CONNECTION_TIME_TO_LIVE = 120;

    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private static final String ZABBIX_HISTORY_TIMESTAMP = "timestamp";

    private final ZabbixAPIEndpoint endpoint;

    private BasicDataSource ds;

    public ZabbixAPIConsumer(ZabbixAPIEndpoint endpoint, Processor processor) {
        super(endpoint, processor);
        this.endpoint = endpoint;

        this.setTimeUnit(TimeUnit.MINUTES);
        this.setInitialDelay(0);

        logger.info("This: " + this);
        logger.info("Endpoint: " + endpoint);
        logger.info("Set delay: " + endpoint.getConfiguration().getDelay());

        this.setDelay(endpoint.getConfiguration().getDelay());
    }

    @Override
    protected int poll() throws Exception {

        String operationPath = endpoint.getOperationPath();

        if ("metricshistory".equals(operationPath))
            return processSearchDevices();

        if ("deletehistory".equals(operationPath))
            return processDeleteHistory();

        // only one operation implemented for now !
        throw new IllegalArgumentException("Incorrect operation: " + operationPath);
    }

    private int processDeleteHistory() {

        ds = null;

        long currentTimeStamp = System.currentTimeMillis() / 1000;

        logger.info(String.format(" **** [%s] [%s] ...",
                endpoint.getOperationPath(),
                endpoint.getStatus()));

        try {
            ds = Main.setupDataSource();
            logger.debug(String.format(" **** [%s]; Active [%d], Idle [%d] ...",
                    ds,
                    ds.getNumActive(),
                    ds.getNumIdle()));

            processSummarizeOnTable("history_float", currentTimeStamp);

            processSummarizeOnTable("history_int", currentTimeStamp);

        } catch (RuntimeException e) {
            genErrorMessage("Error while process history metrics", e);
            return 0;
        } finally {
            closeConnectionsAndDS(null);
        }

        return 1;
    }

    private void processSummarizeOnTable(String tablename, long currentTimeStamp) {
        logger.info(String.format(" **** [%s] Try to get Summarized history ...",
                endpoint.getOperationPath()));
        List<HashMap<String, Object>> summirizedHistoryRows = new ArrayList<>();
        try {
            summirizedHistoryRows = selectOldHistoryByDay(tablename, currentTimeStamp);
        } catch (Exception throwable) {
            genErrorMessage(String.format("[%s] Error while Get Summarized %s execution: ",
                    endpoint.getOperationPath(), tablename), throwable);
        }

        if (!summirizedHistoryRows.isEmpty()) {
            try {
                deleteOldHistory(tablename, currentTimeStamp);
            } catch (SQLException e) {
                genErrorMessage(String.format(
                        "Error while deleting Summarized %s execution: ", tablename), e);
            }

            logger.info(String.format(" **** [%s] Try to Insert Summarized history to DB...",
                    endpoint.getOperationPath()));
            processSqlSummarizedItemsToExchange(summirizedHistoryRows, tablename);
        } else {
            logger.info(String.format(" **** [%s] No rows on period",
                    endpoint.getOperationPath()));
        }
    }

    private void deleteOldHistory(String tablename, long currentTimeStamp) throws SQLException {

        logger.info(String.format(" **** [%s], [%d] [%d] ...",
                ds,
                ds.getNumActive(),
                ds.getNumIdle()));

        Connection con = null;
        PreparedStatement pstmt;
        ResultSet resultset;

        logger.info(String.format(" **** [%s] Try to delete history that was summarized for  %s ...",
                endpoint.getOperationPath(), tablename));

        try {

            con = ds.getConnection();

            pstmt = con.prepareStatement(String.format(
                    "SELECT  \"deleteOldHistory\"('%s', '%d', '%s') as answer;",
                    tablename, currentTimeStamp, this.endpoint.getConfiguration().getDayInPast()),
                    ResultSet.TYPE_SCROLL_INSENSITIVE,
                    ResultSet.CONCUR_UPDATABLE);

            logger.info(String.format("[%s] DB query: %s", endpoint.getOperationPath(), pstmt.toString()));
            resultset = pstmt.executeQuery();
            if (resultset == null || !resultset.isBeforeFirst()) {

                if (resultset != null) {
                    resultset.close();
                }
                pstmt.close();
            } else {
                // read first line
                resultset.first();
                String answer = resultset.getString("answer");

                logger.info(String.format(" **** [%s] Recieved answer: %s ",
                        endpoint.getOperationPath(), answer));

                resultset.close();
                pstmt.close();

                logger.info(String.format(" **** [%s] Closing DB connections for getting history  *****",
                        endpoint.getOperationPath()));
                con.close();
            }

        } catch (SQLException e) {
            logger.error(String.format("[%s] Error while SQL execution: %s ", endpoint.getOperationPath(), e));
            throw e;

        } catch (Exception e) {
            //send error message to the same queue
            logger.error(String.format("[%s] Error while execution: %s ",
                    endpoint.getOperationPath(), e));
            throw e;
        } finally {
            if (con != null)
                con.close();
        }

    }

    private List<HashMap<String, Object>> selectOldHistoryByDay(String tablename, long currentTimeStamp) throws SQLException {

        logger.info(String.format(" **** [%s], [%d] [%d] ...",
                ds,
                ds.getNumActive(),
                ds.getNumIdle()));

        Connection con = null;
        PreparedStatement pstmt;

        logger.info(String.format(" **** [%s] Try to get history for  %s ...",
                endpoint.getOperationPath(), tablename));
        try {

            con = ds.getConnection();

            pstmt = con.prepareStatement(String.format(
                    "SELECT * FROM selectOldHistory('%s', '%d', '%s');",
                    tablename, currentTimeStamp,
                    this.endpoint.getConfiguration().getDayInPast()));

            logger.info(String.format("[%s] DB query: %s",
                    endpoint.getOperationPath(), pstmt.toString()));

            return convertOldHistoryToListOfHashMaps(tablename, con, pstmt);

        } catch (SQLException e) {
            logger.error(String.format("[%s] Error while SQL execution: %s ",
                    endpoint.getOperationPath(), e));
            throw e;

        } catch (Exception e) {
            //send error message to the same queue
            logger.error(String.format("[%s] Error while execution: %s ",
                    endpoint.getOperationPath(), e));
            throw e;
        } finally {
            if (con != null)
                con.close();
        }

    }

    private List<HashMap<String, Object>> convertOldHistoryToListOfHashMaps(String tablename, Connection con, PreparedStatement pstmt) throws SQLException {
        ResultSet resultset;
        resultset = pstmt.executeQuery();
        if (resultset == null || !resultset.isBeforeFirst()) {

            if (resultset != null) {
                resultset.close();
            }
            pstmt.close();
            return Collections.emptyList();
        } else {

            List<HashMap<String, Object>> listc;
            listc = convertRStoList(resultset);

            logger.info(String.format(" **** [%s] Recieved rows for %s: %d",
                    endpoint.getOperationPath(), tablename, listc != null ? listc.size() : 0));

            resultset.close();
            pstmt.close();

            logger.info(String.format(" **** [%s] Closing DB connections for getting history  *****",
                    endpoint.getOperationPath()));
            con.close();
            return listc;

        }
    }

    @Override
    public long beforePoll(long timeout) throws Exception {

        logger.info("*** Before Poll!!!");
        // only one operation implemented for now !

        // send HEARTBEAT
        genHeartbeatMessage(getEndpoint().createExchange(), this.endpoint.getConfiguration().getAdaptername());

        return timeout;
    }

    private int processSearchDevices() {

        ds = null;

        List<Map<String, Object>> listFinal = new ArrayList<>();

        logger.info("Try to get Metrics...");

        DefaultZabbixApi zabbixApi = null;
        try {

            String lastpolltime = endpoint.getConfiguration().getLastpolltime();
            String lastpolltimetozab;

            zabbixApi = setupZabbixApi();

            long lastclockfromDB = getLastClockFromDB();
            long currentTimeStamp = System.currentTimeMillis() / 1000;

            logger.info("**** lastpolltime: " + lastpolltime);
            logger.info("**** lastclockfromDB: " + lastclockfromDB);

            // get last poll timestamp
            if ("0".equals(lastpolltime)) {
                lastpolltime = Long.toString(lastclockfromDB);
                lastpolltimetozab = lastpolltime;
            } else {
                lastpolltimetozab = lastpolltime;
            }

            logger.info("**** lastpolltimetozab: " + lastpolltimetozab);

            String tilltimeToZabbix = getTillTimeToZabbix(lastpolltimetozab, currentTimeStamp);

            // get itemids from DB
            Object[] allitems = getAllItemsIdFromDB();
            String[] allitemids = (String[]) allitems[0];
            HashMap metricsMap = (HashMap) allitems[1];

            logger.info(String.format("**** Received %d metrics from DB", allitemids.length));

            long lastclockfinal = getAndProcessHistoryItems(listFinal, zabbixApi, lastpolltimetozab, tilltimeToZabbix, allitemids, metricsMap);

            // save last received clock from zabbix to DB
            processExchangeUpdateLastTime(currentTimeStamp, tilltimeToZabbix, lastclockfinal);

        } catch (Exception e) {
            genErrorMessage("Error while get Metrics from API", e);
            return 0;
        } finally {
            closeConnectionsAndDS(zabbixApi);
        }

        return 1;
    }

    private long getAndProcessHistoryItems(List<Map<String, Object>> listFinal, DefaultZabbixApi zabbixApi, String lastpolltimetozab,
                                           String tilltimeToZabbix, String[] allitemids, HashMap metricsMap) throws SQLException {
        List<Map<String, Object>> intItemsList;
        List<Map<String, Object>> floatItemsList;
        List<Map<String, Object>> strItemsList;
        List<Map<String, Object>> textItemsList;
        List<Map<String, Object>> logItemsList;
        long lastclock;
        long lastclockfinal = 0;

        /*
             * History object types to return.

                Possible values:
                0 - float;
                1 - string;
                2 - log;
                3 - integer;
                4 - text.

                Default: 3.
             */

        // get ints
        intItemsList = getHistoryByItems(zabbixApi, allitemids, lastpolltimetozab, tilltimeToZabbix, 3);
        if (intItemsList != null && !intItemsList.isEmpty()) {
            listFinal.addAll(intItemsList);
            lastclock = (long) intItemsList.get(0).get(ZABBIX_HISTORY_TIMESTAMP);
            if (lastclock > lastclockfinal)
                lastclockfinal = lastclock;
        }
        // get floats
        floatItemsList = getHistoryByItems(zabbixApi, allitemids, lastpolltimetozab, tilltimeToZabbix, 0);
        if (floatItemsList != null && !floatItemsList.isEmpty()) {
            listFinal.addAll(floatItemsList);
            lastclock = (long) floatItemsList.get(0).get(ZABBIX_HISTORY_TIMESTAMP);
            if (lastclock > lastclockfinal)
                lastclockfinal = lastclock;
        }

        // get str
        strItemsList = getHistoryByItems(zabbixApi, allitemids, lastpolltimetozab, tilltimeToZabbix, 1);
        if (strItemsList != null && !strItemsList.isEmpty()) {
            listFinal.addAll(strItemsList);
            lastclock = (long) strItemsList.get(0).get(ZABBIX_HISTORY_TIMESTAMP);
            if (lastclock > lastclockfinal)
                lastclockfinal = lastclock;
        }

        // get text
        textItemsList = getHistoryByItems(zabbixApi, allitemids, lastpolltimetozab, tilltimeToZabbix, 4);
        if (textItemsList != null && !textItemsList.isEmpty()) {
            listFinal.addAll(textItemsList);
            lastclock = (long) textItemsList.get(0).get(ZABBIX_HISTORY_TIMESTAMP);
            if (lastclock > lastclockfinal)
                lastclockfinal = lastclock;
        }

        // get log
        logItemsList = getHistoryByItems(zabbixApi, allitemids, lastpolltimetozab, tilltimeToZabbix, 2);
        if (logItemsList != null && !logItemsList.isEmpty()) {
            listFinal.addAll(logItemsList);
            lastclock = (long) logItemsList.get(0).get(ZABBIX_HISTORY_TIMESTAMP);
            if (lastclock > lastclockfinal)
                lastclockfinal = lastclock;
        }

        processAllItemsToExchange(metricsMap, intItemsList, floatItemsList, strItemsList, textItemsList, logItemsList);

        return lastclockfinal;
    }

    private void processAllItemsToExchange(HashMap metricsMap, List<Map<String, Object>> intItemsList, List<Map<String, Object>> floatItemsList,
                                           List<Map<String, Object>> strItemsList, List<Map<String, Object>> textItemsList,
                                           List<Map<String, Object>> logItemsList) throws SQLException {
        processSqlItemsToExchange(intItemsList, "INT", metricsMap);
        if (intItemsList != null) {
            logger.info("Sended INT Metrics: " + intItemsList.size());
        }
        processSqlItemsToExchange(floatItemsList, "FLOAT", metricsMap);
        if (floatItemsList != null) {
            logger.info("Sended FLOAT Metrics: " + floatItemsList.size());
        }
        processSqlItemsToExchange(strItemsList, "STR", metricsMap);
        if (strItemsList != null) {
            logger.info("Sended STR Metrics: " + strItemsList.size());
        }
        processSqlItemsToExchange(textItemsList, "TEXT", metricsMap);
        if (textItemsList != null) {
            logger.info("Sended TEXT Metrics: " + textItemsList.size());
        }
        processSqlItemsToExchange(logItemsList, "LOG", metricsMap);
        if (logItemsList != null) {
            logger.info("Sended LOG Metrics: " + logItemsList.size());
        }
    }

    private DefaultZabbixApi setupZabbixApi() {
        DefaultZabbixApi zabbixApi;

        ds = Main.setupDataSource();

        logger.info(String.format(" **** [%s], [%d] [%d] ...",
                ds,
                ds.getNumActive(),
                ds.getNumIdle()));

        String zabbixapiurl = endpoint.getConfiguration().getZabbixapiurl();
        String username = endpoint.getConfiguration().getUsername();
        String password = endpoint.getConfiguration().getPassword();

        HttpClient httpClient2 = HttpClients.custom()
                .setConnectionTimeToLive(CONNECTION_TIME_TO_LIVE, TimeUnit.SECONDS)
                .setMaxConnTotal(MAX_CONN_TOTAL).setMaxConnPerRoute(MAX_CONN_PER_ROUTE)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setSocketTimeout(SOCKET_TIMEOUT).setConnectTimeout(CONNECT_TIMEOUT).build())
                .setRetryHandler(new DefaultHttpRequestRetryHandler(5, true))
                .build();

        zabbixApi = new DefaultZabbixApi(zabbixapiurl, (CloseableHttpClient) httpClient2);
        zabbixApi.init();

        boolean login = zabbixApi.login(username, password);
        if (!login) {
            throw new RuntimeException("Failed to login to Zabbix API.");
        }
        return zabbixApi;
    }

    private String getTillTimeToZabbix(String lastpolltimetozab, long currentTimeStamp) {
        String tilltimeToZabbix = "";
        // if different between saved and current Zabbix time more than N hours
        int maxDiffTime = endpoint.getConfiguration().getMaxDiffTime();
        logger.info("**** currentTimeStamp: " + currentTimeStamp);
        if (currentTimeStamp - Integer.parseInt(lastpolltimetozab) > maxDiffTime) {
            tilltimeToZabbix = Integer.toString(Integer.parseInt(lastpolltimetozab) + maxDiffTime);
            logger.info(String.format("**** Different between saved and current Zabbix time more than %s hours",
                    (float) maxDiffTime / SECONDS_IN_HOUR));
            logger.info(String.format("**** Set 'till_time' property: %s",
                    tilltimeToZabbix));
        }
        return tilltimeToZabbix;
    }

    private void closeConnectionsAndDS(DefaultZabbixApi zabbixApi) {
        logger.debug(String.format(" **** Close zabbixApi Client: %s",
                zabbixApi != null ? zabbixApi.toString() : null));

        if (zabbixApi != null) {
            zabbixApi.destory();
        }

        try {
            ds.close();
        } catch (SQLException e) {
            logger.error("Error while closing Datasource", e);
        }
    }

    private void processExchangeUpdateLastTime(long currentTimeStamp, String tilltimeToZabbix, long lastclock) {

        long lastclockfinal = lastclock;

        if (!"".equals(tilltimeToZabbix))
            lastclockfinal = Integer.parseInt(tilltimeToZabbix);

        // if was no rows
        if (lastclockfinal == 0)
            lastclockfinal = currentTimeStamp;

        logger.info(String.format("Save last clock timestamp: %s", lastclockfinal + 1));

        endpoint.getConfiguration().setLastpolltime(Long.toString(lastclockfinal + 1));

        Map<String, Object> answer = new HashMap<>();
        answer.put(ZABBIX_HISTORY_TIMESTAMP, lastclockfinal);
        answer.put("source", String.format("%s:%s",
                endpoint.getConfiguration().getSource(),
                endpoint.getConfiguration().getZabbixapiurl()));

        Exchange exchange = getEndpoint().createExchange();
        exchange.getIn().setHeader("queueName", "UpdateLastPoll");
        exchange.getIn().setBody(answer);

        try {
            getProcessor().process(exchange);
            if (exchange.getException() != null) {
                genErrorMessage("Ошибка при передаче сообщения в БД", exchange.getException());
            }
        } catch (Exception e) {
            genErrorMessage("Ошибка при передаче сообщения в БД", e);
        }
    }

    private void processSqlItemsToExchange(List<Map<String, Object>> itemsList, String type, HashMap metricsMap) throws SQLException {

        // reverse the list
        Collections.reverse(itemsList);

        logger.info("Create Exchange containers for " + type + " history metrics...");
        String sqlPrefixPart = "insert into history_" + type.toLowerCase() + " ( metricid, value, timestamp) values ";
        String sql = "";
        String sqlLastValues = "";

        HashMap<String, String> metricValues = new HashMap<>();

        if (itemsList == null)
            return;

        for (int i = 0; i < itemsList.size(); i++) {
            logger.debug(String.format("[***] itemid:%s, metricid: %s",
                    itemsList.get(i).get("itemid").toString(),
                    metricsMap.get(itemsList.get(i).get("itemid").toString()).toString()));
            metricValues.put(metricsMap.get(itemsList.get(i).get("itemid").toString()).toString(),
                    itemsList.get(i).get("value").toString());

            sql = sql + String.format(" (%s, '%s', to_timestamp('%s')),",
                    metricsMap.get(itemsList.get(i).get("itemid").toString()),
                    itemsList.get(i).get("value"),
                    itemsList.get(i).get(ZABBIX_HISTORY_TIMESTAMP));

            sqlLastValues = sqlLastValues + String.format(" (%s, '%s'),",
                    metricsMap.get(itemsList.get(i).get("itemid").toString()),
                    itemsList.get(i).get("value"));

            int mod = (i + 1) % endpoint.getConfiguration().getBatchRowCount();

            if (mod == 0 || (i == itemsList.size() - 1)) {

                processBatchSQLExchange(sqlPrefixPart, sql, mod);

                // reset SQL string
                sql = "";
                sqlLastValues = "";
            }
        }

        // delete old lastvalues
        deletePreviousLastValuesFromFB(type, metricValues);

        // update lastvalues
        updateLastValuesToDB(type, metricValues);

    }

    private void deletePreviousLastValuesFromFB(String type, HashMap<String, String> metricValues) throws SQLException {
        Connection con = null;
        PreparedStatement pstmt;

        logger.info("**** Try to delete old Lastvalues from DB  ***** ");
        try {

            con = ds.getConnection();

            List<String> keys = new ArrayList<>();
            for (Map.Entry<String, String> entry : metricValues.entrySet()) {
                keys.add(entry.getKey());
            }
            String join = "";
            join += StringUtils.join(keys, ", ");

            pstmt = con.prepareStatement("DELETE FROM history_" + type.toLowerCase() + "_lastvalue " +
                    "WHERE metricid in (" + join + ")");

            logger.debug(String.format("[%s] [LASTVALUE] DB query: %s",
                    endpoint.getOperationPath(), pstmt.toString()));

            int rowNums = 0;
            if (join.length() != 0) {
                rowNums = pstmt.executeUpdate();
            }
            logger.info(String.format("[LASTVALUE] DB query result rows: %d", rowNums));
            pstmt.close();
        } catch (SQLException e) {
            genErrorMessage("Ошибка удаления записей из БД", e);
        } finally {
            if (con != null)
                con.close();
        }
    }

    private void updateLastValuesToDB(String type, HashMap<String, String> metricValues) {
        String sqlLastValuesPrefixPart = "insert into history_" + type.toLowerCase() + "_lastvalue (metricid, lastvalue) values ";
        String sqlLastValues = "";
        int j = 0;
        logger.info("**** metric uniq size: " + metricValues.size());
        for (Map.Entry<String, String> entry : metricValues.entrySet()) {

            j++;
            sqlLastValues = sqlLastValues + String.format(" (%s, '%s'),",
                    entry.getKey(),
                    entry.getValue());

            // insert for lastvalue
            int mod = (j + 1) % endpoint.getConfiguration().getBatchRowCount();
            if (mod == 0 || (j == metricValues.size() - 1)) {

                // insert for lastvalue
                processBatchSQLExchange(sqlLastValuesPrefixPart, sqlLastValues, mod);
                // reset SQL string
                sqlLastValues = "";
            }
        }
    }

    private void processSqlSummarizedItemsToExchange(List<HashMap<String, Object>> itemsList, String tablename) {

        if (itemsList == null)
            return;

        logger.info(String.format("[%s] Create Exchange containers for %s summarized history metrics...",
                endpoint.getOperationPath(), tablename));

        String sqlPrefixPart = "insert into " + tablename.toLowerCase() + " ( metricid, value, timestamp) values ";
        String sql = "";

        for (int i = 0; i < itemsList.size(); i++) {

            sql = sql + String.format(" (%s, '%s', '%s'),",
                    itemsList.get(i).get("metricid").toString(),
                    itemsList.get(i).get("value"),
                    itemsList.get(i).get(ZABBIX_HISTORY_TIMESTAMP));

            int mod = (i + 1) % endpoint.getConfiguration().getBatchRowCount();

            if (mod == 0 || (i == itemsList.size() - 1)) {

                processBatchSQLExchange(sqlPrefixPart, sql, mod);

                // reset SQL string
                sql = "";
            }
        }

    }

    private void processBatchSQLExchange(String sqlPrefixPart, String sql, int mod) {

        String fullSql = String.format("%s %s",
                sqlPrefixPart,
                sql.substring(0, sql.length() - 1));
        logger.debug(String.format("[%s] fullSql: %s",
                endpoint.getOperationPath(), fullSql));

        int rowCount = mod == 0 ? endpoint.getConfiguration().getBatchRowCount() : mod;

        logger.debug(String.format("[%s] Create Batch Insert SQL Exchange container: %d history rows",
                endpoint.getOperationPath(), rowCount));
        Exchange exchange = getEndpoint().createExchange();
        exchange.getIn().setBody(fullSql);
        exchange.getIn().setHeader("queueName", "Metrics");

        try {
            getProcessor().process(exchange);
            logger.info(String.format("[%s] Inserted %d history rows to database",
                    endpoint.getOperationPath(), rowCount));
        } catch (Exception e) {
            genErrorMessage(String.format("[%s] Error while Insert items to database",
                    endpoint.getOperationPath()), e);
        }

    }

    private Object[] getAllItemsIdFromDB() throws Exception {

        String[] itemids = new String[0];

        Connection con = null;
        PreparedStatement pstmt;
        ResultSet resultset;

        logger.info(" **** Try to get metrics IDs from DB  ***** ");
        try {

            con = ds.getConnection();
            pstmt = con.prepareStatement("SELECT id,itemid FROM metrics WHERE source = ?;");
            pstmt.setString(1, endpoint.getConfiguration().getSource());

            logger.debug("DB query: " + pstmt.toString());

            resultset = pstmt.executeQuery();
            logger.debug(resultset.getMetaData().getColumnLabel(1));

            List<HashMap<String, Object>> listc;
            listc = convertRStoList(resultset);

            // map itemid => metricid
            Map<String, Integer> metricsMap = new HashMap<>();
            if (listc != null) {
                itemids = new String[listc.size()];
                for (int i = 0; i < listc.size(); i++) {
                    itemids[i] = listc.get(i).get("itemid").toString();
                    int metricid = (int) listc.get(i).get("id");
                    metricsMap.put(itemids[i], metricid);
                    logger.debug("Found ItemID in DB: " + i + ": " + itemids[i] + ", metricid: " + metricid);
                }
            }

            logger.debug("MetricsHashMap: " + metricsMap.toString());

            resultset.close();
            pstmt.close();

            logger.info(" **** Closing DB connections for getting metrics  *****");
            con.close();

            Object[] array = new Object[2];
            array[0] = itemids;
            array[1] = metricsMap;

            return array;

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (con != null)
                con.close();
        }

    }

    private Long getLastClockFromDB() throws Exception {

        long lastclock;

        Connection con = null;
        PreparedStatement pstmt;
        ResultSet resultset;

        logger.info(" **** Try to get Last metrics Clock from DB  ***** ");
        try {

            con = ds.getConnection();

            pstmt = con.prepareStatement(new StringBuilder()
                            .append("SELECT cast(extract(EPOCH FROM lastclock) AS INTEGER) AS lastclock ")
                            .append("FROM metrics_lastpoll ").append("WHERE source = ? ")
                            .toString(),
                    ResultSet.TYPE_SCROLL_INSENSITIVE,
                    ResultSet.CONCUR_UPDATABLE);

            pstmt.setString(1, String.format("%s:%s",
                    endpoint.getConfiguration().getSource(),
                    endpoint.getConfiguration().getZabbixapiurl()));

            logger.debug("DB query: " + pstmt.toString());
            resultset = pstmt.executeQuery();
            if (resultset == null || !resultset.isBeforeFirst()) {

                if (resultset != null) {
                    resultset.close();
                }
                pstmt.close();

                lastclock = System.currentTimeMillis() / 1000;

                // new insert
                String insertLastClockSql = String.format("insert into metrics_lastpoll " +
                                " (lastclock, source) VALUES (to_timestamp(%d), '%s:%s')",
                        lastclock,
                        endpoint.getConfiguration().getSource(),
                        endpoint.getConfiguration().getZabbixapiurl());

                pstmt = con.prepareStatement(insertLastClockSql);
                logger.debug("Inserting new lastclock SQL: " + insertLastClockSql);
                resultset = pstmt.executeQuery();

                logger.info("No lastclock records found. Insert new last clock: " + lastclock);

                if (resultset != null) {
                    resultset.close();
                }

                pstmt.close();
                con.close();
                return lastclock;

            } else {

                resultset.first();
                lastclock = resultset.getInt("lastclock");
                logger.info("Received saved last clock from DB: " + lastclock);

                resultset.close();
                pstmt.close();
                logger.info(" **** Closing DB connections for getting last clock  *****");
                con.close();
                return lastclock;
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (con != null)
                con.close();
        }
    }

    private List<Map<String, Object>> getHistoryByItems(DefaultZabbixApi zabbixApi, String[] allitemids,
                                                        String timeFrom, String timeTill, int historytype) {

        String limitElements = endpoint.getConfiguration().getZabbixMaxElementsLimit();

        Request getRequest;
        RequestBuilder getRequestBuilder;
        JSONObject getResponse;

        try {

            logger.info(String.format("**** Try to get metrics History for item type: " +
                    "%d from timestamp: %s to timestamp: %s", historytype, timeFrom, timeTill));

            getRequestBuilder = RequestBuilder.newBuilder().method("history.get")
                    .paramEntry("history", historytype)
                    .paramEntry("output", "extend")

                    .paramEntry("itemids", allitemids)
                    .paramEntry("time_from", timeFrom)

                    .paramEntry("sortfield", "clock")
                    .paramEntry("sortorder", "DESC")
                    .paramEntry("limit", limitElements);
            if (!"".equals(timeTill))
                getRequestBuilder.paramEntry("time_till", timeTill);

            getRequest = getRequestBuilder.build();

        } catch (Exception ex) {
            throw new RuntimeException("Failed create JSON request for get History of items.", ex);
        }
        JSONArray history;
        try {
            getResponse = zabbixApi.call(getRequest);

            logger.debug("****** Finded Zabbix getRequest: " + getRequest);

            history = getResponse.getJSONArray("result");
            logger.debug("****** Finded Zabbix getResponse: " + getResponse);

        } catch (Exception e) {
            throw new RuntimeException("Failed get JSON response result for get History of items.", e);
        }

        List<Map<String, Object>> deviceList = new ArrayList<>();
        List<Map<String, Object>> listFinal = new ArrayList<>();

        logger.info("Finded Zabbix History records: " + history.size());

        for (int i = 0; i < history.size(); i++) {

            JSONObject historyitem = history.getJSONObject(i);
            Integer itemid = Integer.parseInt(historyitem.getString("itemid"));
            String rowvalue = historyitem.getString("value");

            Long timestamp = (long) Integer.parseInt(historyitem.getString("clock"));

            Object value;
            /*
             * Possible values:
            0 - float;
            1 - string;
            2 - log;
            3 - integer;
            4 - text.
             */
            switch (historytype) {
                case 3:
                    value = Long.parseLong(rowvalue);
                    break;
                case 0:
                    value = Float.parseFloat(rowvalue);
                    break;

                default:
                    value = rowvalue;
                    break;

            }

            Map<String, Object> answer = new HashMap<>();
            answer.put("itemid", itemid);
            answer.put("value", value);
            answer.put(ZABBIX_HISTORY_TIMESTAMP, timestamp);
            answer.put("historytype", historytype);

            deviceList.add(answer);

        }

        listFinal.addAll(deviceList);

        return listFinal;
    }

    private void genErrorMessage(String message, Exception exception) {
        genAndSendErrorMessage(this, message, exception,
                endpoint.getConfiguration().getAdaptername());
    }

    private List<HashMap<String, Object>> convertRStoList(ResultSet resultset) {

        List<HashMap<String, Object>> list = new ArrayList<>();

        try {
            ResultSetMetaData md = resultset.getMetaData();
            int columns = md.getColumnCount();

            logger.info("DB SQL columns count: " + columns);

            while (resultset.next()) {
                HashMap<String, Object> row = new HashMap<>(columns);
                for (int i1 = 1; i1 <= columns; ++i1) {
                    logger.debug("DB SQL getColumnLabel: " + md.getColumnLabel(i1));
                    logger.debug("DB SQL getObject: " + resultset.getObject(i1));
                    row.put(md.getColumnLabel(i1), resultset.getObject(i1));
                }
                list.add(row);
            }

            return list;

        } catch (SQLException e) {
            genErrorMessage("Ошибка конвертирования результата запроса", e);
            return Collections.emptyList();
        }
    }

}