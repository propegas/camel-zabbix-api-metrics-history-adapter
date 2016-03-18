package ru.atc.camel.zabbix.metrics.history;

//import java.io.IOException;
//import java.io.UnsupportedEncodingException;
//import java.security.KeyManagementException;
//import java.security.KeyStore;
//import java.security.KeyStoreException;
//import java.security.MessageDigest;
//import java.security.NoSuchAlgorithmException;
//import java.sql.Array;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.github.hengyunabc.zabbix.api.DefaultZabbixApi;
import io.github.hengyunabc.zabbix.api.Request;
import io.github.hengyunabc.zabbix.api.RequestBuilder;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.impl.ScheduledPollConsumer;
import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.at_consulting.itsm.event.Event;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

//import java.util.concurrent.ScheduledExecutorService;
//import java.util.regex.Matcher;
//import java.util.regex.Pattern;
//import javax.net.ssl.SSLContext;
//import org.apache.commons.lang.ArrayUtils;
//import org.apache.http.HttpVersion;
//import org.apache.http.client.ClientProtocolException;
//import org.apache.http.client.CookieStore;
//import org.apache.http.client.config.RequestConfig;
//import org.apache.http.client.methods.HttpPut;
//import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
//import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
//import org.apache.http.impl.client.CloseableHttpClient;
//import org.apache.http.impl.client.DefaultHttpClient;
//import org.apache.http.impl.client.HttpClientBuilder;
//import org.apache.http.impl.client.HttpClients;
//import org.apache.http.params.CoreProtocolPNames;
//import org.apache.http.ssl.SSLContextBuilder;
//import com.google.gson.JsonObject;
//import net.sf.ehcache.search.expression.And;
//import ru.at_consulting.itsm.device.Device;
//import scala.xml.dtd.ParameterEntityDecl;

public class ZabbixAPIConsumer extends ScheduledPollConsumer {

    private static Logger logger = LoggerFactory.getLogger(Main.class);

    private static Logger logger2 = LoggerFactory.getLogger(Main.class);

    private ZabbixAPIEndpoint endpoint;

    private BasicDataSource ds = Main.setupDataSource();

    public ZabbixAPIConsumer(ZabbixAPIEndpoint endpoint, Processor processor) {
        super(endpoint, processor);
        this.endpoint = endpoint;

        // this.afterPoll();
        this.setTimeUnit(TimeUnit.MINUTES);
        this.setInitialDelay(0);
        //this.setUseFixedDelay();
        logger.info("This: " + this);
        logger.info("Endpoint: " + endpoint);
        logger.info("Set delay: " + endpoint.getConfiguration().getDelay());
        //ScheduledExecutorService scheduledExecutorService;
        //scheduledExecutorService
        //this.setScheduledExecutorService(scheduledExecutorService);
        this.setDelay(endpoint.getConfiguration().getDelay());
    }

    public static void genHeartbeatMessage(Exchange exchange, String source) {
        // TODO Auto-generated method stub
        long timestamp = System.currentTimeMillis();
        timestamp = timestamp / 1000;
        // String textError = "Возникла ошибка при работе адаптера: ";
        Event genevent = new Event();
        genevent.setMessage("Сигнал HEARTBEAT от адаптера");
        genevent.setEventCategory("ADAPTER");
        genevent.setObject("HEARTBEAT");
        genevent.setSeverity(PersistentEventSeverity.OK.name());
        genevent.setTimestamp(timestamp);
        //genevent.setEventsource(String.format("%s", endpoint.getConfiguration().getAdaptername()));
        genevent.setEventsource(String.format("%s", source));

        logger.info(" **** Create Exchange for Heartbeat Message container");
        // Exchange exchange = getEndpoint().createExchange();
        exchange.getIn().setBody(genevent, Event.class);

        exchange.getIn().setHeader("Timestamp", timestamp);
        exchange.getIn().setHeader("queueName", "Heartbeats");
        exchange.getIn().setHeader("Type", "Heartbeats");
        //exchange.getIn().setHeader("Source", endpoint.getConfiguration().getAdaptername());
        exchange.getIn().setHeader("Source", source);

        try {
            // Processor processor = getProcessor();
            // .process(exchange);
            // processor.process(exchange);
        } catch (Exception e) {
            // TODO Auto-generated catch block
            // e.printStackTrace();
        }
    }

    @Override
    protected int poll() throws Exception {

        String operationPath = endpoint.getOperationPath();

        if (operationPath.equals("metricshistory"))
            return processSearchDevices();

        if (operationPath.equals("deletehistory"))
            return processDeleteHistory();

        // only one operation implemented for now !
        throw new IllegalArgumentException("Incorrect operation: " + operationPath);
    }

    private int processDeleteHistory() {

        //BasicDataSource ds = Main.setupDataSource();

        long currentTimeStamp = System.currentTimeMillis() / 1000;
        List<HashMap<String, Object>> summirizedHistoryRows = new ArrayList<>();

        logger.info(String.format(" **** [%s] [%s] ...",
                endpoint.getOperationPath(),
                endpoint.getStatus()));

        try {
            logger.info(String.format(" **** [%s], [%d] [%d] ...",
                    ds,
                    ds.getNumActive(),
                    ds.getNumIdle()));

            processSummarizeOnTable("history_float", currentTimeStamp, summirizedHistoryRows);

            processSummarizeOnTable("history_int", currentTimeStamp, summirizedHistoryRows);

            /*
            try {
                deleteOldHistory("history_str", currentTimeStamp);
            } catch (SQLException e) {
                e.printStackTrace();

                throw new RuntimeException(String.format(
                        "Error while deleting Summarized %s execution: %s ", "history_str", e));

                //logger.error(String.format("Error while deleting Summarized history_float execution: %s ", e));

            }

            try {
                deleteOldHistory("history_text", currentTimeStamp);
            } catch (SQLException e) {
                e.printStackTrace();

                throw new RuntimeException(String.format(
                        "Error while deleting Summarized %s execution: %s ", "history_text", e));

                //logger.error(String.format("Error while deleting Summarized history_float execution: %s ", e));

            }

            try {
                deleteOldHistory("history_log", currentTimeStamp);
            } catch (SQLException e) {
                e.printStackTrace();

                throw new RuntimeException(String.format(
                        "Error while deleting Summarized %s execution: %s ", "history_log", e));

                //logger.error(String.format("Error while deleting Summarized history_float execution: %s ", e));

            }
            */
        } catch (RuntimeException e) {
            e.printStackTrace();
        } finally {
            try {
                ds.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }


        return 1;
    }

    private void processSummarizeOnTable(String tablename, long currentTimeStamp, List<HashMap<String, Object>> summirizedHistoryRows) {
        logger.info(String.format(" **** [%s] Try to get Summarized history ...",
                endpoint.getOperationPath()));
        try {
            summirizedHistoryRows = selectOldHistoryByDay(tablename, currentTimeStamp);
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            logger.error(String.format("[%s] Error while Get Summarized %s execution: %s ",
                    endpoint.getOperationPath(), tablename, throwable));

        }

        if (summirizedHistoryRows != null && summirizedHistoryRows.size() != 0) {
            try {
                deleteOldHistory(tablename, currentTimeStamp);
            } catch (SQLException e) {
                e.printStackTrace();
                throw new RuntimeException(String.format(
                        "Error while deleting Summarized %s execution: %s ", tablename, e));

                //logger.error(String.format("Error while deleting Summarized history_float execution: %s ", e));

            }

            logger.info(String.format(" **** [%s] Try to Insert Summarized history to DB...",
                    endpoint.getOperationPath()));
            processSqlSummarizedItemsToExchange(summirizedHistoryRows, tablename);
        }
    }

    private void deleteOldHistory(String tablename, long currentTimeStamp) throws SQLException {
        //BasicDataSource ds = Main.setupDataSource();

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
            // +" LIMIT ?;");
            //pstmt.setString(1, "");

            logger.info(String.format("[%s] DB query: %s", endpoint.getOperationPath(), pstmt.toString()));
            resultset = pstmt.executeQuery();
            if (resultset == null || !resultset.isBeforeFirst()) {

                if (resultset != null) {
                    resultset.close();
                }
                pstmt.close();
                //return null;
            } else {

                //resultset.first();
                // List<HashMap<String, Object>> listc;
                // listc = convertRStoList(resultset);

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
                //return listc;
                //return lastclock;
            }

        } catch (SQLException e) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
            logger.error(String.format("[%s] Error while SQL execution: %s ", endpoint.getOperationPath(), e));

            if (con != null) con.close();

            //return null;
            throw e;

        } catch (Throwable e) { //send error message to the same queue
            // TODO Auto-generated catch block
            logger.error(String.format("[%s] Error while execution: %s ",
                    endpoint.getOperationPath(), e));
            //genErrorMessage(e.getMessage());
            // 0;
            throw e;
        } finally {
            if (con != null) con.close();
            //return null;
            //return list;
        }


    }

    private List<HashMap<String, Object>> selectOldHistoryByDay(String tablename, long currentTimeStamp) throws SQLException, Throwable {

        //BasicDataSource ds = Main.setupDataSource();

        logger.info(String.format(" **** [%s], [%d] [%d] ...",
                ds,
                ds.getNumActive(),
                ds.getNumIdle()));

        Connection con = null;
        PreparedStatement pstmt;
        ResultSet resultset;

        logger.info(String.format(" **** [%s] Try to get history for  %s ...",
                endpoint.getOperationPath(), tablename));
        try {

            con = ds.getConnection();

            pstmt = con.prepareStatement(String.format(
                    "SELECT * FROM selectOldHistory('%s', '%d', '%s');",
                    tablename, currentTimeStamp,
                    this.endpoint.getConfiguration().getDayInPast()));
            // +" LIMIT ?;");
            //pstmt.setString(1, "");

            logger.info(String.format("[%s] DB query: %s",
                    endpoint.getOperationPath(), pstmt.toString()));
            resultset = pstmt.executeQuery();
            if (resultset == null || !resultset.isBeforeFirst()) {

                if (resultset != null) {
                    resultset.close();
                }
                pstmt.close();
                return null;
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
                //return lastclock;
            }

        } catch (SQLException e) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
            logger.error(String.format("[%s] Error while SQL execution: %s ",
                    endpoint.getOperationPath(), e));

            if (con != null) con.close();

            //return null;
            throw e;

        } catch (Throwable e) { //send error message to the same queue
            // TODO Auto-generated catch block
            logger.error(String.format("[%s] Error while execution: %s ",
                    endpoint.getOperationPath(), e));
            //genErrorMessage(e.getMessage());
            // 0;
            throw e;
        } finally {
            if (con != null) con.close();
            //return null;
            //return list;
        }


    }

    @Override
    public long beforePoll(long timeout) throws Exception {

        logger.info("*** Before Poll!!!");
        // only one operation implemented for now !
        // throw new IllegalArgumentException("Incorrect operation: ");

        // send HEARTBEAT
        genHeartbeatMessage(getEndpoint().createExchange(), this.endpoint.getConfiguration().getAdaptername());

        return timeout;
    }

    private int processSearchDevices() throws Exception {

        // Long timestamp;

        logger.info(String.format(" **** [%s], [%d] [%d] ...",
                ds,
                ds.getNumActive(),
                ds.getNumIdle()));

        List<Map<String, Object>> intItemsList;
        List<Map<String, Object>> floatItemsList;
        List<Map<String, Object>> strItemsList;
        List<Map<String, Object>> textItemsList;
        List<Map<String, Object>> logItemsList;

        //List<Map<String, Object>> webitemsList = new ArrayList<Map<String, Object>>();

        List<Map<String, Object>> listFinal = new ArrayList<>();

        //List<Device> listFinal = new ArrayList<Device>();

        String eventsuri = endpoint.getConfiguration().getZabbixapiurl();
        String uri = String.format("%s", eventsuri);

        System.out.println("***************** URL: " + uri);

        logger.info("Try to get Metrics...");
        // logger.info("Get events URL: " + uri);

        //JsonObject json = null;

        DefaultZabbixApi zabbixApi = null;
        try {
            String zabbixapiurl = endpoint.getConfiguration().getZabbixapiurl();
            String username = endpoint.getConfiguration().getUsername();
            String password = endpoint.getConfiguration().getPassword();
            String lastpolltime = endpoint.getConfiguration().getLastpolltime();
            String lastpolltimetozab;

            HttpClient httpClient2 = HttpClients.custom()
                    .setConnectionTimeToLive(120, TimeUnit.SECONDS)
                    .setMaxConnTotal(40).setMaxConnPerRoute(40)
                    .setDefaultRequestConfig(RequestConfig.custom()
                            .setSocketTimeout(360000).setConnectTimeout(20000).build())
                    .setRetryHandler(new DefaultHttpRequestRetryHandler(5, true))
                    .build();

            // String url = "http://192.168.90.102/zabbix/api_jsonrpc.php";
            zabbixApi = new DefaultZabbixApi(zabbixapiurl, (CloseableHttpClient) httpClient2);
            zabbixApi.init();

            boolean login = zabbixApi.login(username, password);
            //System.err.println("login:" + login);
            if (!login) {

                throw new RuntimeException("Failed to login to Zabbix API.");
            }

            long lastclockfromDB = getLastClockFromDB();
            long currentTimeStamp = System.currentTimeMillis() / 1000;

            // get last poll timestamp
            if (lastpolltime.equals("0")) {
                //lastpolltime = getLastClockFromZabbix(zabbixApi);
                lastpolltime = lastclockfromDB + "";
                //lastpolltimetozab = (Integer.parseInt(lastpolltime) - 3600) + "";
                lastpolltimetozab = lastpolltime;
            } else {
                lastpolltimetozab = lastpolltime;
                //lastpolltime = getLastClockFromZabbix(zabbixApi);
            }

            String tilltimeToZabbix = "";
            // if different between saved and current Zabbix time more than 3 hours
            int maxDiffTime = endpoint.getConfiguration().getMaxDiffTime();
            logger.info("**** currentTimeStamp: " + currentTimeStamp);
            if (currentTimeStamp - Integer.parseInt(lastpolltimetozab) > maxDiffTime) {
                tilltimeToZabbix = Integer.parseInt(lastpolltimetozab) + maxDiffTime + "";
                logger.info("**** Different between saved and current Zabbix time more than "
                        + (float) maxDiffTime / 3600 + " hours");
                logger.info(String.format("**** Set 'till_time' property: %s", tilltimeToZabbix));
            }


            // get itemids from DB
            Object[] allitems = getAllItemsIdFromDB();
            String[] allitemids = (String[]) allitems[0];
            HashMap metricsMap = (HashMap) allitems[1];

            logger.info(String.format("**** Received %d metrics from DB", allitemids.length));

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

            long lastclock;
            long lastclockfinal = 0;

            // get ints
            intItemsList = getHistoryByItems(zabbixApi, allitemids, lastpolltimetozab, tilltimeToZabbix, 3);
            if (intItemsList != null && !intItemsList.isEmpty()) {
                listFinal.addAll(intItemsList);
                lastclock = (long) intItemsList.get(0).get("timestamp");
                if (lastclock > lastclockfinal)
                    lastclockfinal = lastclock;
            }
            // get floats
            floatItemsList = getHistoryByItems(zabbixApi, allitemids, lastpolltimetozab, tilltimeToZabbix, 0);
            if (floatItemsList != null && !floatItemsList.isEmpty()) {
                listFinal.addAll(floatItemsList);
                lastclock = (long) floatItemsList.get(0).get("timestamp");
                if (lastclock > lastclockfinal)
                    lastclockfinal = lastclock;
            }
            //}

            // get str
            strItemsList = getHistoryByItems(zabbixApi, allitemids, lastpolltimetozab, tilltimeToZabbix, 1);
            if (strItemsList != null && !strItemsList.isEmpty()) {
                listFinal.addAll(strItemsList);
                lastclock = (long) strItemsList.get(0).get("timestamp");
                if (lastclock > lastclockfinal)
                    lastclockfinal = lastclock;
            }
            //}

            // get text
            textItemsList = getHistoryByItems(zabbixApi, allitemids, lastpolltimetozab, tilltimeToZabbix, 4);
            if (textItemsList != null && !textItemsList.isEmpty()) {
                listFinal.addAll(textItemsList);
                lastclock = (long) textItemsList.get(0).get("timestamp");
                if (lastclock > lastclockfinal)
                    lastclockfinal = lastclock;
            }
            //}

            // get log
            logItemsList = getHistoryByItems(zabbixApi, allitemids, lastpolltimetozab, tilltimeToZabbix, 2);
            if (logItemsList != null && !logItemsList.isEmpty()) {
                listFinal.addAll(logItemsList);
                lastclock = (long) logItemsList.get(0).get("timestamp");
                if (lastclock > lastclockfinal)
                    lastclockfinal = lastclock;
            }
            //}

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


            // save last received clock from zabbix to DB
            if (!tilltimeToZabbix.equals(""))
                lastclockfinal = Integer.parseInt(tilltimeToZabbix);

            logger.info(String.format("Save last clock timestamp: %s", lastclockfinal + 1));

            endpoint.getConfiguration().setLastpolltime(lastclockfinal + 1 + "");

            Map<String, Object> answer = new HashMap<>();
            //answer.put("timestamp", (long) Integer.parseInt(lastpolltime));
            answer.put("timestamp", lastclockfinal);
            answer.put("source", String.format("%s:%s",
                    endpoint.getConfiguration().getSource(),
                    endpoint.getConfiguration().getZabbixapiurl()));

            Exchange exchange = getEndpoint().createExchange();
            exchange.getIn().setHeader("queueName", "UpdateLastPoll");
            exchange.getIn().setBody(answer);

            try {
                getProcessor().process(exchange);
            } catch (Exception e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }


        } catch (NullPointerException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            logger.error(String.format("Error while get Metrics History from API: %s ", e));
            genErrorMessage(e.getMessage() + " " + e.toString());
            //httpClient.close();
            return 0;
        } catch (Throwable e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            logger.error(String.format("Error while get Metrics History from API: %s ", e));
            genErrorMessage(e.getMessage() + " " + e.toString());
            //httpClient.close();
            if (zabbixApi != null) {
                zabbixApi.destory();
            }
            return 0;
        } finally {
            logger.debug(String.format(" **** Close zabbixApi Client: %s",
                    zabbixApi != null ? zabbixApi.toString() : null));
            // httpClient.close();
            if (zabbixApi != null) {
                zabbixApi.destory();
            }
            // dataSource.close();
            // return 0;
        }

        return 1;
    }

    private void processSqlItemsToExchange(List<Map<String, Object>> itemsList, String type, HashMap metricsMap) {

        logger.info("Create Exchange containers for " + type + " history metrics...");
        //int batchRowCount = 0;
        String sqlPrefixPart = "insert into history_" + type.toLowerCase() + " ( metricid, value, timestamp) values ";
        String sql = "";

        for (int i = 0; i < (itemsList != null ? itemsList.size() : 0); i++) {

            sql = sql + String.format(" (%s, '%s', to_timestamp('%s')),",
                    metricsMap.get(itemsList.get(i).get("itemid").toString()),
                    itemsList.get(i).get("value"),
                    itemsList.get(i).get("timestamp"));

            //logger.info("i : " + i + " / " + itemsList.size());

            int mod = (i + 1) % endpoint.getConfiguration().getBatchRowCount();

            if (mod == 0 || (i == itemsList.size() - 1)) {
                //batchRowCount++;

                processBatchSQLExchange(sqlPrefixPart, sql, mod);

                // reset batch count
                sql = "";
            }
        }


    }

    private void processSqlSummarizedItemsToExchange(List<HashMap<String, Object>> itemsList, String tablename) {

        logger.info(String.format("[%s] Create Exchange containers for %s summarized history metrics...",
                endpoint.getOperationPath(), tablename));
        //int batchRowCount = 0;
        String sqlPrefixPart = "insert into " + tablename.toLowerCase() + " ( metricid, value, timestamp) values ";
        String sql = "";

        for (int i = 0; i < (itemsList != null ? itemsList.size() : 0); i++) {

            sql = sql + String.format(" (%s, '%s', '%s'),",
                    itemsList.get(i).get("metricid").toString(),
                    itemsList.get(i).get("value"),
                    itemsList.get(i).get("timestamp"));

            //logger.info("i : " + i + " / " + itemsList.size());

            int mod = (i + 1) % endpoint.getConfiguration().getBatchRowCount();

            if (mod == 0 || (i == itemsList.size() - 1)) {
                //batchRowCount++;

                //logger.info("batchRowCount : " + i);
                processBatchSQLExchange(sqlPrefixPart, sql, mod);
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
            //return true;
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            logger.error(String.format("[%s] Error while Insert items to database%s",
                    endpoint.getOperationPath()));
            //return false;
        }

        // reset batch count
        sql = "";
        //batchRowCount = 0;
    }

    private Object[] getAllItemsIdFromDB() throws Throwable {

        String[] itemids = new String[0];

        //BasicDataSource ds = Main.setupDataSource();
        //logger.info(" **** getMaxConnLifetimeMillis: ***** " + ds.getMaxConnLifetimeMillis() );
        //logger.info(" **** getMaxIdle:  ***** " + ds.getMaxIdle() );

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
            //resultset.next();
            //int i = 0;

            List<HashMap<String, Object>> listc;

            listc = convertRStoList(resultset);

            //listc.toArray(itemids);

            // map itemid => metricid
            Map metricsMap = new HashMap();
            if (listc != null) {
                itemids = new String[listc.size()];
                for (int i = 0; i < listc.size(); i++) {
                    //itemids[i] = items.getJSONObject(i).getString("itemid");
                    itemids[i] = listc.get(i).get("itemid").toString();
                    int metricid = (int) listc.get(i).get("id");
                    metricsMap.put(itemids[i], metricid);
                    logger.debug("Found ItemID in DB: " + i + ": " + itemids[i] + ", metricid: " + metricid);
                }
            }

            //i++;
            logger.debug("MetricsHashMap: " + metricsMap.toString());


            //logger.debug("Get last clock from DB: " +  lastclock);
            /*
            for(int i = 0; i < itemids.length; i++) {
				//itemids[i] = items.getJSONObject(i).getString("itemid");
				logger.debug("Found ItemID in DB: " + i + ": " + itemids[i]);
			}
			*/
            resultset.close();
            pstmt.close();

            logger.info(" **** Closing DB connections for getting metrics  *****");
            con.close();

            Object[] array = new Object[2];
            array[0] = itemids;
            array[1] = metricsMap;

            return array;

        } catch (SQLException e) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
            logger.error(String.format("Error while SQL execution: %s ", e));

            if (con != null) con.close();

            //return null;
            throw e;

        } catch (Throwable e) { //send error message to the same queue
            // TODO Auto-generated catch block
            logger.error(String.format("Error while execution: %s ", e));
            //genErrorMessage(e.getMessage());
            // 0;
            throw e;
        } finally {
            if (con != null) con.close();

            //return list;
        }
    }

    private Long getLastClockFromDB() throws Throwable {

        long lastclock;

        //BasicDataSource ds = Main.setupDataSource();

        Connection con = null;
        PreparedStatement pstmt;
        ResultSet resultset;

        logger.info(" **** Try to get Last metrics Clock from DB  ***** ");
        try {

            con = ds.getConnection();

            pstmt = con.prepareStatement("SELECT cast(extract(EPOCH FROM lastclock) AS INTEGER) AS lastclock "
                    + "FROM metrics_lastpoll "
                    + "WHERE source = ?");
            // +" LIMIT ?;");
            //pstmt.setString(1, "");
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

                lastclock = resultset.getInt("lastclock");
                logger.info("Received saved last clock from DB: " + lastclock);

                resultset.close();
                pstmt.close();

                logger.info(" **** Closing DB connections for getting last clock  *****");
                con.close();
                return lastclock;
            }


        } catch (SQLException e) {
            // TODO Auto-generated catch block
            //e.printStackTrace();
            logger.error(String.format("Error while SQL execution: %s ", e));

            if (con != null) con.close();

            //return null;
            throw e;

        } catch (Throwable e) { //send error message to the same queue
            // TODO Auto-generated catch block
            logger.error(String.format("Error while execution: %s ", e));
            //genErrorMessage(e.getMessage());
            // 0;
            throw e;
        } finally {
            if (con != null) con.close();

            //return list;
        }
    }

    private String getLastClockFromZabbix(DefaultZabbixApi zabbixApi) {
        // TODO Auto-generated method stub
        Request getRequest;
        JSONObject getResponse;
        // JsonObject params = new JsonObject();
        logger.info(" **** Try to get Last metrics Clock from Zabbix  ***** ");
        try {
            //JSONObject filter = new JSONObject();

            getRequest = RequestBuilder.newBuilder().method("history.get")
                    .paramEntry("output", "extend")
                    .paramEntry("sortfield", "clock")
                    .paramEntry("sortorder", "DESC")
                    .paramEntry("limit", 1)

                    .build();

        } catch (Exception ex) {
            ex.printStackTrace();
            throw new RuntimeException("Failed create JSON request for get Last Clock.");
        }
        JSONArray items;
        try {
            getResponse = zabbixApi.call(getRequest);
            //System.err.println(getRequest);
            logger.debug("****** Finded Zabbix getRequest: " + getRequest);

            items = getResponse.getJSONArray("result");
            logger.debug("****** Finded Zabbix getResponse: " + getResponse);

        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            throw new RuntimeException("Failed get JSON response result for Last Clock.");
        }

        String lastclock = items.getJSONObject(0).getString("clock");

        logger.debug("Last Clock: " + lastclock);


        //listFinal.addAll(deviceList);

        return lastclock;
    }

    private List<Map<String, Object>> getHistoryByItems(DefaultZabbixApi zabbixApi, String[] allitemids,
                                                        String time_from, String time_till, int historytype) {

        String limitElements = endpoint.getConfiguration().getZabbixMaxElementsLimit();

        Request getRequest;
        RequestBuilder getRequestBuilder;
        JSONObject getResponse;
        // JsonObject params = new JsonObject();
        try {

            logger.info(String.format("**** Try to get metrics History for item type: " +
                    "%d from timestamp: %s to timestamp: %s", historytype, time_from, time_till));

            //JSONObject filter = new JSONObject();
            //filter.put("type", new String[] { "9" });

            getRequestBuilder = RequestBuilder.newBuilder().method("history.get")
                    .paramEntry("history", historytype)
                    .paramEntry("output", "extend")
                    //.paramEntry("output", new String[] { "itemid", "value", "clock" })
                    .paramEntry("itemids", allitemids)
                    .paramEntry("time_from", time_from)
                    //.paramEntry("time_till", time_till)
                    .paramEntry("sortfield", "clock")
                    .paramEntry("sortorder", "DESC")
                    .paramEntry("limit", limitElements);
            if (!time_till.equals(""))
                getRequestBuilder.paramEntry("time_till", time_till);

            getRequest = getRequestBuilder.build();

        } catch (Exception ex) {
            ex.printStackTrace();
            throw new RuntimeException("Failed create JSON request for get History of items.");
        }
        JSONArray history;
        try {
            //System.err.println(getRequest);
            getResponse = zabbixApi.call(getRequest);

            logger.debug("****** Finded Zabbix getRequest: " + getRequest);

            history = getResponse.getJSONArray("result");
            logger.debug("****** Finded Zabbix getResponse: " + getResponse);

        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            throw new RuntimeException("Failed get JSON response result for get History of items.");
        }

        List<Map<String, Object>> deviceList = new ArrayList<>();

        List<Map<String, Object>> listFinal = new ArrayList<>();

        //List<Device> listFinal = new ArrayList<Device>();

        logger.info("Finded Zabbix History records: " + history.size());

        for (int i = 0; i < history.size(); i++) {

            JSONObject historyitem = history.getJSONObject(i);
            Integer itemid = Integer.parseInt(historyitem.getString("itemid"));
            String rowvalue = historyitem.getString("value");
            //String clock = historyitem.getString("clock");
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
            answer.put("timestamp", timestamp);
            answer.put("historytype", historytype);

            deviceList.add(answer);

        }

        listFinal.addAll(deviceList);

        return listFinal;
    }

    private void genErrorMessage(String message) {
        // TODO Auto-generated method stub
        long timestamp = System.currentTimeMillis();
        timestamp = timestamp / 1000;
        String textError = "Возникла ошибка при работе адаптера: ";
        Event genevent = new Event();
        genevent.setMessage(textError + message);
        genevent.setEventCategory("ADAPTER");
        genevent.setSeverity(PersistentEventSeverity.CRITICAL.name());
        genevent.setTimestamp(timestamp);
        genevent.setEventsource(String.format("%s", endpoint.getConfiguration().getSource()));
        genevent.setStatus("OPEN");
        genevent.setHost("adapter");

        logger.info(" **** Create Exchange for Error Message container");
        Exchange exchange = getEndpoint().createExchange();
        exchange.getIn().setBody(genevent, Event.class);

        exchange.getIn().setHeader("EventIdAndStatus", "Error_" + timestamp);
        exchange.getIn().setHeader("Timestamp", timestamp);
        exchange.getIn().setHeader("queueName", "Events");
        exchange.getIn().setHeader("Type", "Error");

        try {
            getProcessor().process(exchange);
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

    }

    private List<HashMap<String, Object>> convertRStoList(ResultSet resultset) throws SQLException {

        List<HashMap<String, Object>> list = new ArrayList<>();

        try {
            ResultSetMetaData md = resultset.getMetaData();
            int columns = md.getColumnCount();
            //result.getArray(columnIndex)
            //resultset.get
            logger.info("DB SQL columns count: " + columns);

            //resultset.last();
            //int count = resultset.getRow();
            //logger.debug("MYSQL rows2 count: " + count);
            //resultset.beforeFirst();

            //ArrayList<String> arrayList = new ArrayList<String>();

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
            // TODO Auto-generated catch block
            e.printStackTrace();

            return null;

        } finally {

        }
    }


    public enum PersistentEventSeverity {
        OK, INFO, WARNING, MINOR, MAJOR, CRITICAL;

        public static PersistentEventSeverity fromValue(String v) {
            return valueOf(v);
        }

        public String value() {
            return name();
        }
    }

}