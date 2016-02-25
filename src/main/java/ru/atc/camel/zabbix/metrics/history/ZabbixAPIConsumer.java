package ru.atc.camel.zabbix.metrics.history;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
//import java.security.KeyManagementException;
//import java.security.KeyStore;
//import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//import javax.net.ssl.SSLContext;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.impl.ScheduledPollConsumer;
import org.apache.commons.dbcp.BasicDataSource;
//import org.apache.commons.lang.ArrayUtils;
//import org.apache.http.HttpVersion;
import org.apache.http.client.ClientProtocolException;
//import org.apache.http.client.CookieStore;
//import org.apache.http.client.config.RequestConfig;
//import org.apache.http.client.methods.HttpPut;
//import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
//import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
//import org.apache.http.impl.client.DefaultHttpClient;
//import org.apache.http.impl.client.HttpClientBuilder;
//import org.apache.http.impl.client.HttpClients;
//import org.apache.http.params.CoreProtocolPNames;
//import org.apache.http.ssl.SSLContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
//import com.google.gson.JsonObject;
import io.github.hengyunabc.zabbix.api.DefaultZabbixApi;
import io.github.hengyunabc.zabbix.api.Request;
import io.github.hengyunabc.zabbix.api.RequestBuilder;
//import net.sf.ehcache.search.expression.And;
//import ru.at_consulting.itsm.device.Device;
import ru.at_consulting.itsm.event.Event;
//import scala.xml.dtd.ParameterEntityDecl;

public class ZabbixAPIConsumer extends ScheduledPollConsumer {

	private static Logger logger = LoggerFactory.getLogger(Main.class);

	private static ZabbixAPIEndpoint endpoint;

	//private static String SavedWStoken;

	private static CloseableHttpClient httpClient;

	public enum PersistentEventSeverity {
		OK, INFO, WARNING, MINOR, MAJOR, CRITICAL;

		public String value() {
			return name();
		}

		public static PersistentEventSeverity fromValue(String v) {
			return valueOf(v);
		}
	}

	public ZabbixAPIConsumer(ZabbixAPIEndpoint endpoint, Processor processor) {
		super(endpoint, processor);
		ZabbixAPIConsumer.endpoint = endpoint;
		// this.afterPoll();
		this.setTimeUnit(TimeUnit.MINUTES);
		this.setInitialDelay(0);
		this.setDelay(endpoint.getConfiguration().getDelay());
	}

	@Override
	protected int poll() throws Exception {

		String operationPath = endpoint.getOperationPath();

		if (operationPath.equals("metricshistory"))
			return processSearchDevices();

		// only one operation implemented for now !
		throw new IllegalArgumentException("Incorrect operation: " + operationPath);
	}

	@Override
	public long beforePoll(long timeout) throws Exception {

		logger.info("*** Before Poll!!!");
		// only one operation implemented for now !
		// throw new IllegalArgumentException("Incorrect operation: ");

		// send HEARTBEAT
		genHeartbeatMessage(getEndpoint().createExchange());

		return timeout;
	}

	private int processSearchDevices() throws ClientProtocolException, IOException, Exception {

		// Long timestamp;

		List<Map<String, Object>> intItemsList = new ArrayList<Map<String, Object>>();
		List<Map<String, Object>> floatItemsList = new ArrayList<Map<String, Object>>();
		List<Map<String, Object>> strItemsList = new ArrayList<Map<String, Object>>();
		List<Map<String, Object>> textItemsList = new ArrayList<Map<String, Object>>();
		List<Map<String, Object>> logItemsList = new ArrayList<Map<String, Object>>();
		
		//List<Map<String, Object>> webitemsList = new ArrayList<Map<String, Object>>();
		
		List<Map<String, Object>> listFinal = new ArrayList<Map<String, Object>>();
		
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
			String lastpolltimetozab = "";
			
			long lastclockfromDB = getLastClockFromDB();
			
			// String url = "http://192.168.90.102/zabbix/api_jsonrpc.php";
			zabbixApi = new DefaultZabbixApi(zabbixapiurl);
			zabbixApi.init();

			boolean login = zabbixApi.login(username, password);
			//System.err.println("login:" + login);
			if (!login) {
				
				throw new RuntimeException("Failed to login to Zabbix API.");
			}
			
			// get last poll timestamp
			if (lastpolltime.equals("0")) {
				//lastpolltime = getLastClockFromZabbix(zabbixApi);
				lastpolltime = lastclockfromDB + "";
				//lastpolltimetozab = (Integer.parseInt(lastpolltime) - 3600) + "";
				lastpolltimetozab = lastpolltime;
			}
			else {
				lastpolltimetozab = lastpolltime;
				//lastpolltime = getLastClockFromZabbix(zabbixApi);
			}
			 
			

			// Get all Items marked as [FOR_INTEGRATION] from Zabbix
			String[] itemids = {}; 
			String[] webitemids = {};
			//itemids = getAllItems(zabbixApi);
			//if (itemids != null)java
				//listFinal.addAll(itemsList);
			
			// Get all WEB Items from Zabbix
			//webitemids = getAllWebItems(zabbixApi);
			//if (webitemids != null)
				
				//listFinal.addAll(webitemsList);
			
			//String[] allitemids = (String[])ArrayUtils.addAll(itemids, webitemids);
			
			// get itemids from DB
			String[] allitemids = getAllItemsIdFromDB();
			
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
			long lastclock = 0;
			long lastclockfinal = 0;
			intItemsList= getHistoryByItems(zabbixApi, allitemids, lastpolltimetozab, 0 );
			if (intItemsList != null && !intItemsList.isEmpty()){
				listFinal.addAll(intItemsList);
				lastclock = (long) intItemsList.get(0).get("timestamp");
				if (lastclock > lastclockfinal)
					lastclockfinal = lastclock;
			}
			// get floats
			floatItemsList= getHistoryByItems(zabbixApi, allitemids, lastpolltimetozab, 3 );
			if (floatItemsList != null && !floatItemsList.isEmpty()){
				listFinal.addAll(floatItemsList);
				lastclock = (long) floatItemsList.get(0).get("timestamp");
				if (lastclock > lastclockfinal)
					lastclockfinal = lastclock;
			}
			//}
			
			// get str
			strItemsList= getHistoryByItems(zabbixApi, allitemids, lastpolltimetozab, 1 );
			if (strItemsList != null && !strItemsList.isEmpty()){
				listFinal.addAll(strItemsList);
				lastclock = (long) strItemsList.get(0).get("timestamp");
				if (lastclock > lastclockfinal)
					lastclockfinal = lastclock;
			}
			//}
			
			// get text
			textItemsList= getHistoryByItems(zabbixApi, allitemids, lastpolltimetozab, 4 );
			if (textItemsList != null && !textItemsList.isEmpty()){
				listFinal.addAll(textItemsList);
				lastclock = (long) textItemsList.get(0).get("timestamp");
				if (lastclock > lastclockfinal)
					lastclockfinal = lastclock;
			}
			//}
			
			// get log
			logItemsList= getHistoryByItems(zabbixApi, allitemids, lastpolltimetozab, 2 );
			if (logItemsList != null && !logItemsList.isEmpty()){
				listFinal.addAll(logItemsList);
				lastclock = (long) logItemsList.get(0).get("timestamp");
				if (lastclock > lastclockfinal)
					lastclockfinal = lastclock;
			}
			//}
			
			logger.info("Create Exchange containers...");
			for (int i = 0; i < listFinal.size(); i++) {
				logger.debug("Create Exchange container " + i);
				
				logger.debug(String.format("Send History for item:%d with value:%s and timestamp %s", 
								listFinal.get(i).get("itemid"), 
								listFinal.get(i).get("value"), 
								listFinal.get(i).get("timestamp")));
				
				Exchange exchange = getEndpoint().createExchange();
				Integer historytype = (Integer) listFinal.get(i).get("historytype");
				String table = "";
				switch (historytype) {
	        	case 3:  table = "history_int";break;
	        	case 0:  table = "history_float";break;
	        	case 2:  table = "history_log";break;
	        	case 1:  table = "history_str";break;
	        	case 4:  table = "history_text";break;
	        	
	        	default:  table = "history_text";break;

				}
				
				exchange.getIn().setBody(listFinal.get(i));
				exchange.getIn().setHeader("Table", table);
				//exchange.getIn().setHeader("DeviceType", listFinal.get(i).getDeviceType());
				exchange.getIn().setHeader("queueName", "Metrics");
				

				try {
					getProcessor().process(exchange);
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
			}
			
			logger.info("Sended Metrics: " + listFinal.size());
			
			// save last received clock from zabbix to DB 
			
			logger.info(String.format("Save last clock timestamp: %s", lastclockfinal));
			
			endpoint.getConfiguration().setLastpolltime(lastclockfinal + "");
			
			Map<String, Object> answer = new HashMap<String, Object>();
		    //answer.put("timestamp", (long) Integer.parseInt(lastpolltime));
			answer.put("timestamp", lastclockfinal);
			answer.put("source", endpoint.getConfiguration().getZabbixapiurl());
			
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
		} catch (Error e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			logger.error(String.format("Error while get Metrics History from API: %s ", e));
			genErrorMessage(e.getMessage() + " " + e.toString());
			//httpClient.close();
			zabbixApi.destory();
			return 0;
		} catch (Throwable e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			logger.error(String.format("Error while get Metrics History from API: %s ", e));
			genErrorMessage(e.getMessage() + " " + e.toString());
			//httpClient.close();
			zabbixApi.destory();
			return 0;
		} finally {
			logger.debug(String.format(" **** Close zabbixApi Client: %s", zabbixApi.toString()));
			// httpClient.close();
			zabbixApi.destory();
			// dataSource.close();
			// return 0;
		}

		return 1;
	}

	private String[] getAllItemsIdFromDB() throws SQLException, Throwable {

		String [] itemids= new String[] {};

		BasicDataSource ds = Main.setupDataSource();
		
		Connection con = null; 
	    PreparedStatement pstmt;
	    ResultSet resultset = null;
	    
	    logger.info(" **** Try to get metrics IDs from DB  ***** " );
	    try {
	    	
	    	con = (Connection) ds.getConnection();
	    	
			pstmt = con.prepareStatement("select itemid from metrics;");
						
			logger.debug("DB query: " +  pstmt.toString()); 
			resultset = pstmt.executeQuery();
			logger.debug(resultset.getMetaData().getColumnLabel(1) );
			//resultset.next();
			//int i = 0;
			
			List<HashMap<String,Object>> listc = new ArrayList<HashMap<String,Object>>();
			
			listc = convertRStoList(resultset);
			
			//listc.toArray(itemids);
			
			itemids= new String[listc.size()] ;
			for(int i = 0; i < listc.size(); i++) {
				//itemids[i] = items.getJSONObject(i).getString("itemid");
				itemids[i] = listc.get(i).get("itemid").toString();
				logger.debug("Found ItemID in DB: " + i + ": " + itemids[i]);
			}
				
			//i++;
				
			
			
			//logger.debug("Get last clock from DB: " +  lastclock); 
			/*
			for(int i = 0; i < itemids.length; i++) {
				//itemids[i] = items.getJSONObject(i).getString("itemid");
				logger.debug("Found ItemID in DB: " + i + ": " + itemids[i]);
			}
			*/
			resultset.close();
	        pstmt.close();
	        
	        if (con != null) con.close();
    	
	        return itemids;
    	
	    } catch (SQLException e) {
			// TODO Auto-generated catch block
			//e.printStackTrace();
			logger.error( String.format("Error while SQL execution: %s ", e));
			
			if (con != null) con.close();
			
			//return null;
			throw e;

		} catch (Throwable e) { //send error message to the same queue
			// TODO Auto-generated catch block
			logger.error( String.format("Error while execution: %s ", e));
			//genErrorMessage(e.getMessage());
			// 0;
			throw e;
		} finally {
	        if (con != null) con.close();
	        
	        //return list;
	    }
	}

	private Long getLastClockFromDB() throws SQLException, Throwable {
		
		long lastclock = 0;

		BasicDataSource ds = Main.setupDataSource();
		
		Connection con = null; 
	    PreparedStatement pstmt;
	    ResultSet resultset = null;
	    
	    logger.info(" **** Try to get Last metrics Clock from DB  ***** " );
	    try {
	    	
	    	con = (Connection) ds.getConnection();
	    	
			pstmt = con.prepareStatement("select cast(extract(epoch from lastclock) as integer) as lastclock "
					+ "from metrics_lastpoll "
					+ "where source = ?");
	                   // +" LIMIT ?;");
	        //pstmt.setString(1, "");
	        pstmt.setString(1, endpoint.getConfiguration().getZabbixapiurl());
						
			logger.debug("DB query: " +  pstmt.toString()); 
			resultset = pstmt.executeQuery();
			resultset.next();
			
			lastclock = resultset.getInt("lastclock");
			logger.info("Received saved last clock from DB: " +  lastclock); 
			
			resultset.close();
	        pstmt.close();
	        
	        if (con != null) con.close();
    	
    	return lastclock;
    	
	} catch (SQLException e) {
		// TODO Auto-generated catch block
		//e.printStackTrace();
		logger.error( String.format("Error while SQL execution: %s ", e));
		
		if (con != null) con.close();
		
		//return null;
		throw e;

	} catch (Throwable e) { //send error message to the same queue
		// TODO Auto-generated catch block
		logger.error( String.format("Error while execution: %s ", e));
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
		try {
			JSONObject filter = new JSONObject();
						
			getRequest = RequestBuilder.newBuilder().method("history.get")
					.paramEntry("output",  "extend")
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
				String time_from, int historytype) {
		
		
		Request getRequest;
		JSONObject getResponse;
		// JsonObject params = new JsonObject();
		try {
			
			logger.info(String.format("**** Try to get metrics History for item type: %d from timestamp: %s", 
						historytype, time_from));
			
			//JSONObject filter = new JSONObject();
			//filter.put("type", new String[] { "9" });
			
			getRequest = RequestBuilder.newBuilder().method("history.get")
					.paramEntry("history", historytype)
					.paramEntry("output", "extend")
					.paramEntry("itemids", allitemids)
					.paramEntry("time_from", time_from)
					//.paramEntry("time_till", time_till)
					.paramEntry("sortfield", "clock")
					.paramEntry("sortorder", "DESC")
					.paramEntry("limit", 20000)
					.build();

		} catch (Exception ex) {
			ex.printStackTrace();
			throw new RuntimeException("Failed create JSON request for get History of items.");
		}
		JSONArray history;
		try {
			getResponse = zabbixApi.call(getRequest);
			//System.err.println(getRequest);
			logger.debug("****** Finded Zabbix getRequest: " + getRequest);

			history = getResponse.getJSONArray("result");
			logger.debug("****** Finded Zabbix getResponse: " + getResponse);

		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			throw new RuntimeException("Failed get JSON response result for get History of items.");
		}
		
		List<Map<String, Object>> deviceList = new ArrayList<Map<String, Object>>();
		
		List<Map<String, Object>> listFinal = new ArrayList<Map<String, Object>>();
		
		//List<Device> listFinal = new ArrayList<Device>();
		
		logger.info("Finded Zabbix History records: " + history.size());

		for (int i = 0; i < history.size(); i++) {

			JSONObject historyitem = history.getJSONObject(i);
			Integer itemid = Integer.parseInt(historyitem.getString("itemid"));
			String rowvalue = historyitem.getString("value");
			//String clock = historyitem.getString("clock");
			Long timestamp = (long) Integer.parseInt(historyitem.getString("clock"));
			
			Object value = null;
			/*
			 * Possible values: 
			0 - float; 
			1 - string; 
			2 - log; 
			3 - integer; 
			4 - text. 
			 */
			switch (historytype) {
        	case 3:  value = (long)Long.parseLong(rowvalue);break;
        	case 0:  value = (float)Float.parseFloat(rowvalue);break;
        	
        	default:  value = (String)rowvalue;break;

			}
						
		
			Map<String, Object> answer = new HashMap<String, Object>();
			answer.put("itemid", itemid);
			answer.put("value", value);
		    answer.put("timestamp", timestamp);
		    answer.put("historytype", historytype);
				
			deviceList.add(answer);
		
		}
		
		listFinal.addAll(deviceList);
		
		return listFinal;
	}

	private String[] getAllWebItems(DefaultZabbixApi zabbixApi) {
		
		Request getRequest;
		JSONObject getResponse;
		// JsonObject params = new JsonObject();
		try {
			JSONObject filter = new JSONObject();
			filter.put("type", new String[] { "9" });
			
			getRequest = RequestBuilder.newBuilder().method("item.get")
					.paramEntry("filter", filter)
					.paramEntry("output", new String[] { "itemid" })
					.paramEntry("monitored", true)
					.paramEntry("webitems", true)
					.build();

		} catch (Exception ex) {
			ex.printStackTrace();
			throw new RuntimeException("Failed create JSON request for get all Web Items.");
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
			throw new RuntimeException("Failed get JSON response result for all Web Items.");
		}
		logger.info("Finded Zabbix Items: " + items.size());
		
		//String[] itemids = (String[]) items.toArray();
		String[] itemids = new String[items.size()];
		for(int i = 0; i < items.size(); i++) {
			itemids[i] = items.getJSONObject(i).getString("itemid");
			logger.debug("Item " + i + ": " + itemids[i]);
		}
		
		//listFinal.addAll(deviceList);
		
		return itemids;
			
	}
	
	private String[] getAllItems(DefaultZabbixApi zabbixApi) {
		// TODO Auto-generated method stub
		Request getRequest;
		JSONObject getResponse;
		// JsonObject params = new JsonObject();
		try {
			JSONObject filter = new JSONObject();
			filter.put("description", new String[] { endpoint.getConfiguration().getZabbix_item_description_pattern() });
			
			getRequest = RequestBuilder.newBuilder().method("item.get")
					.paramEntry("search", filter)
					.paramEntry("output", new String[] { "itemid" })
					.paramEntry("monitored", true)
										
					.build();

		} catch (Exception ex) {
			ex.printStackTrace();
			throw new RuntimeException("Failed create JSON request for get all Hosts.");
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
			throw new RuntimeException("Failed get JSON response result for all CI Items.");
		}
		//List<Map<String, Object>> deviceList = new ArrayList<Map<String, Object>>();
		
		//List<Map<String, Object>> listFinal = new ArrayList<Map<String, Object>>();
		
		//List<Device> listFinal = new ArrayList<Device>();
		
		logger.info("Finded Zabbix Items: " + items.size());
		
		//String[] itemids = (String[]) items.toArray();
		String[] itemids = new String[items.size()];
		for(int i = 0; i < items.size(); i++) {
			itemids[i] = items.getJSONObject(i).getString("itemid");
			logger.debug("Item " + i + ": " + itemids[i]);
		}
		
		//listFinal.addAll(deviceList);
		
		return itemids;
			
	}

	private String getTransformedItemName(String name, String key) {
		// TODO Auto-generated method stub
		
		String transformedname = "";
		//String webstep = "";
		
		// get params from key to item $1 placeholder
		// Example:
		// vfs.fs.size[/oracle,pfree]
		
		Pattern p = Pattern.compile("(.*)\\[(.*)\\]");
		Matcher matcher = p.matcher(key);
		
		String keyparams = "";
		String webscenario = "";
		String webstep = "";
		
		String[] params = new String[] { } ;
		
		// if Web Item has webscenario pattern
		// Example:
		// web.test.in[WebScenario,,bps]
		if (matcher.matches()) {
			
			logger.debug("*** Finded Zabbix Item key with Pattern: " + key);
			// save as ne CI name
			keyparams = matcher.group(2).toString();
			
			// get scenario and step from key params
			//String[] params = new String[] { } ;
			params = keyparams.split(",");
			logger.debug(String.format("*** Finded Zabbix Item key params (size): %d ", params.length));
						
			//logger.debug(String.format("*** Finded Zabbix Item key params: %s:%s ", webscenario, webstep));


		}
		// if Item has no CI pattern
		else {
			
			
		}
		
		logger.debug("Item name: " + name);
		
		String param = "";
		int paramnumber;
		Matcher m = Pattern.compile("\\$\\d+").matcher(name);
		while (m.find()) {
			param = m.group(0);
			paramnumber = Integer.parseInt(param.substring(1));
			logger.debug("Found Param: " + paramnumber);
			logger.debug("Found Param Value: " + param);
			logger.debug("Found Param Value Replace: " + params[paramnumber-1]);
			
			name = name.replaceAll("\\$"+paramnumber, params[paramnumber-1]);
			
		}

		
		//logger.debug("New Zabbix Web Item Scenario: " + webelements[0]);
		logger.debug("New Zabbix Item Name: " + name);
		
		return name;

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

	public static void genHeartbeatMessage(Exchange exchange) {
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
		genevent.setEventsource(String.format("%s", endpoint.getConfiguration().getAdaptername()));

		logger.info(" **** Create Exchange for Heartbeat Message container");
		// Exchange exchange = getEndpoint().createExchange();
		exchange.getIn().setBody(genevent, Event.class);

		exchange.getIn().setHeader("Timestamp", timestamp);
		exchange.getIn().setHeader("queueName", "Heartbeats");
		exchange.getIn().setHeader("Type", "Heartbeats");
		exchange.getIn().setHeader("Source", endpoint.getConfiguration().getAdaptername());

		try {
			// Processor processor = getProcessor();
			// .process(exchange);
			// processor.process(exchange);
		} catch (Exception e) {
			// TODO Auto-generated catch block
			// e.printStackTrace();
		}
	}

	private static String hashString(String message, String algorithm)
            throws Exception {
 
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] hashedBytes = digest.digest(message.getBytes("UTF-8"));
 
            return convertByteArrayToHexString(hashedBytes);
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException ex) {
            throw new RuntimeException(
                    "Could not generate hash from String", ex);
        }
	}
	
	private static String convertByteArrayToHexString(byte[] arrayBytes) {
        StringBuffer stringBuffer = new StringBuffer();
        for (int i = 0; i < arrayBytes.length; i++) {
            stringBuffer.append(Integer.toString((arrayBytes[i] & 0xff) + 0x100, 16)
                    .substring(1));
        }
        return stringBuffer.toString();
    }
	
private List<HashMap<String, Object>> convertRStoList(ResultSet resultset) throws SQLException {
		
		List<HashMap<String,Object>> list = new ArrayList<HashMap<String,Object>>();
		
		try {
			ResultSetMetaData md = resultset.getMetaData();
	        int columns = md.getColumnCount();
	        //result.getArray(columnIndex)
	        //resultset.get
	        logger.debug("DB SQL columns count: " + columns); 
	        
	        //resultset.last();
	        //int count = resultset.getRow();
	        //logger.debug("MYSQL rows2 count: " + count); 
	        //resultset.beforeFirst();
	        
	        int i = 0, n = 0;
	        //ArrayList<String> arrayList = new ArrayList<String>(); 
	
	        while (resultset.next()) {              
	        	HashMap<String,Object> row = new HashMap<String, Object>(columns);
	            for(int i1=1; i1<=columns; ++i1) {
	            	logger.debug("DB SQL getColumnLabel: " + md.getColumnLabel(i1)); 
	            	logger.debug("DB SQL getObject: " + resultset.getObject(i1)); 
	                row.put(md.getColumnLabel(i1),resultset.getObject(i1));
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

}