package ru.atc.camel.zabbix.metrics.history;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.jdbc.JdbcComponent;
import org.apache.camel.component.jms.JmsComponent;
import org.apache.camel.component.properties.PropertiesComponent;
import org.apache.camel.component.sql.SqlComponent;
import org.apache.camel.model.dataformat.JsonDataFormat;
import org.apache.camel.model.dataformat.JsonLibrary;
import org.apache.commons.dbcp2.BasicDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.at_consulting.itsm.event.Event;

import javax.jms.ConnectionFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;

//import java.io.File;
//import javax.sql.DataSource;
//import org.apache.camel.CamelContext;
//import org.apache.camel.Endpoint;
//import org.apache.camel.spring.SpringCamelContext;
//import org.springframework.context.ApplicationContext;
//import org.springframework.context.support.ClassPathXmlApplicationContext;
//import ru.at_consulting.itsm.device.Device;
//import org.apache.camel.processor.idempotent.FileIdempotentRepository;

public class Main {

    public static String activemq_port = null;
    public static String activemq_ip = null;
    public static String sql_ip = null;
    public static String sql_port = null;
    public static String sql_database = null;
    public static String sql_user = null;
    public static String sql_password = null;
    public static String usejms = null;
    public static String source;
    public static String useSummarizeRoute = "false";
    public static String useMainRoute = "true";
    public static int maxConnLifetime = 900000;

    private static Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {

        logger.info("Starting Custom Apache Camel component example");
        logger.info("Press CTRL+C to terminate the JVM");
        /*
        if ( args.length == 6  ) {
			activemq_port = (String)args[1];
			activemq_ip = (String)args[0];
			sql_ip = (String)args[2];
			sql_database = (String)args[3];
			sql_user = (String)args[4];
			sql_password = (String)args[5];
		}
		*/


        // get Properties from file
        Properties prop = new Properties();
        InputStream input = null;

        try {

            input = new FileInputStream("zabbixapi.properties");

            // load a properties file
            prop.load(input);

            // get the property value and print it out
            System.out.println(prop.getProperty("sql_ip"));
            System.out.println(prop.getProperty("sql_database"));
            System.out.println(prop.getProperty("sql_user"));
            System.out.println(prop.getProperty("sql_password"));

            sql_ip = prop.getProperty("sql_ip");
            sql_database = prop.getProperty("sql_database");
            sql_port = prop.getProperty("sql_port");
            sql_user = prop.getProperty("sql_user");
            sql_password = prop.getProperty("sql_password");
            usejms = prop.getProperty("usejms");
            activemq_ip = prop.getProperty("activemq_ip");
            activemq_port = prop.getProperty("activemq_port");
            source = prop.getProperty("source");
            useSummarizeRoute = prop.getProperty("useSummarizeRoute");
            useMainRoute = prop.getProperty("useMainRoute");
            maxConnLifetime = Integer.parseInt(prop.getProperty("maxConnLifetime"));

        } catch (IOException ex) {
            ex.printStackTrace();
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        //System.exit(0);

        if (activemq_port == null || Objects.equals(activemq_port, ""))
            activemq_port = "61616";
        if (activemq_ip == null || Objects.equals(activemq_ip, ""))
            activemq_ip = "172.20.19.195";
        if (sql_ip == null || Objects.equals(sql_ip, ""))
            sql_ip = "192.168.157.73";
        if (sql_port == null || Objects.equals(sql_port, ""))
            sql_port = "5432";
        if (sql_database == null || Objects.equals(sql_database, ""))
            sql_database = "monitoring";
        if (sql_user == null || Objects.equals(sql_user, ""))
            sql_user = "postgres";
        if (sql_password == null || Objects.equals(sql_password, ""))
            sql_password = "";


        logger.info("activemq_ip: " + activemq_ip);
        logger.info("activemq_port: " + activemq_port);

        org.apache.camel.main.Main main = new org.apache.camel.main.Main();
        main.enableHangupSupport();
        //main.addOption(option);

        main.addRouteBuilder(new RouteBuilder() {

            @Override
            public void configure() throws Exception {

                JsonDataFormat myJson = new JsonDataFormat();
                myJson.setPrettyPrint(true);
                myJson.setLibrary(JsonLibrary.Jackson);
                myJson.setJsonView(Event.class);
                //myJson.setPrettyPrint(true);

                PropertiesComponent properties = new PropertiesComponent();
                properties.setLocation("classpath:zabbixapi.properties");
                getContext().addComponent("properties", properties);

                ConnectionFactory connectionFactory = new ActiveMQConnectionFactory
                        ("tcp://" + activemq_ip + ":" + activemq_port);
                getContext().addComponent("activemq", JmsComponent.jmsComponentAutoAcknowledge(connectionFactory));


                //getContext().reg

                SqlComponent sql = new SqlComponent();
                BasicDataSource ds = setupDataSource();
                sql.setDataSource(ds);
                getContext().addComponent("sql", sql);

                JdbcComponent jdbc = new JdbcComponent();
                jdbc.setDataSource(ds);
                getContext().addComponent("jdbc", jdbc);

                getContext().setAllowUseOriginalMessage(false);


                // Heartbeats
                if (usejms.equals("true")) {
                    from("timer://foo?period={{heartbeatsdelay}}")
                            //.choice()
                            .process(new Processor() {
                                public void process(Exchange exchange) throws Exception {
                                    ZabbixAPIConsumer.genHeartbeatMessage(exchange, source);
                                }
                            })
                            //.bean(WsdlNNMConsumer.class, "genHeartbeatMessage", exchange)
                            .marshal(myJson)
                            .to("activemq:{{heartbeatsqueue}}")
                            .log("*** Heartbeat: ${id}");
                }


                // get metrics history
                if ("true".equals(useMainRoute)) {
                    from("zabbixapi://metricshistory?"
                            + "delay={{delay}}&"
                            + "zabbixapiurl={{zabbixapiurl}}&"
                            + "username={{username}}&"
                            + "password={{password}}&"
                            + "adaptername={{adaptername}}&"
                            + "zabbix_item_ke_pattern={{zabbix_item_ke_pattern}}&"
                            + "source={{source}}&"
                            + "batchRowCount={{batchRowCount}}&"
                            + "maxDiffTime={{maxDiffTime}}&"
                            + "zabbixMaxElementsLimit={{zabbixMaxElementsLimit}}&"
                            + "lastpolltime={{lastpolltime}}&"
                            + "zabbix_item_description_pattern={{zabbix_item_description_pattern}}")

                            .choice()
                            .when(header("queueName").isEqualTo("Metrics"))

                            .to("jdbc:BasicDataSource")


                            //.recipientList(simple("sql:insert into ${header.Table} {{sql.insertMetricHistory}}"))
                            .log(LoggingLevel.DEBUG, "**** Inserted new Batch rows, SQL: ${body} .")
                            .endChoice()
                            //.log("*** Metric: ${id} ${header.DeviceId}")
                            .when(header("queueName").isEqualTo("UpdateLastPoll"))
                            .to("sql:update metrics_lastpoll {{sql.UpdateLastPoll}}")
                            .otherwise()
                            .choice()
                            .when(constant(usejms).isEqualTo("true"))
                            .marshal(myJson)
                            .to("activemq:{{eventsqueue}}")
                            .log(LoggingLevel.ERROR, "*** Error: ${id} ${header.DeviceId}")
                            .endChoice()
                            .endChoice()
                            .end()

                            .log(LoggingLevel.DEBUG, "Sended message: ${id} ");
                    //.to("activemq:{{devicesqueue}}");
                }

                // select, summarize and delete all history metrics
                if ("true".equals(useSummarizeRoute)) {
                    from("zabbixapi://deletehistory?"
                            + "delay={{delete_delay}}&"
                            + "adaptername={{adaptername}}&"
                            + "source={{source}}&"
                            + "dayInPast={{dayInPast}}&"
                            + "batchRowCount={{batchRowCount}}"
                    )

                            .choice()
                            .when(header("queueName").isEqualTo("Metrics"))
                            //.to("sql:{{sql.insertMetric}}?dataSource=dataSource")
                            //.log(LoggingLevel.DEBUG, "**** use table: ${header.Table}")
                            //.log(LoggingLevel.DEBUG, "**** use query: {{sql.insertMetricHistory}}")
                        /*
                        .choice()

							.when(header("Table").isEqualTo("history_float"))
								.to("sql:insert into history_float {{sql.insertMetricHistory}}?consumer.delay=0&consumer.initialDelay=0")
							.when(header("Table").isEqualTo("history_int"))
								.to("sql:insert into history_int {{sql.insertMetricHistory}}?consumer.delay=0&consumer.initialDelay=0")
							.when(header("Table").isEqualTo("history_str"))
								.to("sql:insert into history_str {{sql.insertMetricHistory}}?consumer.delay=0&consumer.initialDelay=0")
							.when(header("Table").isEqualTo("history_log"))
								.to("sql:insert into history_log {{sql.insertMetricHistory}}?consumer.delay=0&consumer.initialDelay=0")
							.when(header("Table").isEqualTo("history_text"))
								.to("sql:insert into history_text {{sql.insertMetricHistory}}?consumer.delay=0&consumer.initialDelay=0")
						.end()
						*/
                            .to("jdbc:BasicDataSource")


                            //.recipientList(simple("sql:insert into ${header.Table} {{sql.insertMetricHistory}}"))
                            .log(LoggingLevel.DEBUG, "**** Inserted new Batch rows, SQL: ${body} .")
                            .endChoice()
                            //.log("*** Metric: ${id} ${header.DeviceId}")
                            .when(header("queueName").isEqualTo("UpdateLastPoll"))
                            .to("sql:update metrics_lastpoll {{sql.UpdateLastPoll}}")
                            .otherwise()
                            .choice()
                            .when(constant(usejms).isEqualTo("true"))
                            .marshal(myJson)
                            .to("activemq:{{eventsqueue}}")
                            .log(LoggingLevel.ERROR, "*** Error: ${id} ${header.DeviceId}")
                            .endChoice()
                            .endChoice()
                            .end()

                            .log(LoggingLevel.DEBUG, "Sended message: ${id} ");
                }

            }
        });

        main.run();
    }


    public static BasicDataSource setupDataSource() {

        String url = String.format("jdbc:postgresql://%s:%s/%s",
                sql_ip, sql_port, sql_database);

        BasicDataSource ds = new BasicDataSource();
        ds.setMaxTotal(20);
        //ds.setMax
        ds.setMaxIdle(10);
        ds.setMinIdle(5);
        ds.setSoftMinEvictableIdleTimeMillis(300000);
        ds.setMaxConnLifetimeMillis(maxConnLifetime);
        ds.setLogExpiredConnections(true);
        //ds.setLogWriter(logger);
        ds.setDefaultAutoCommit(true);
        ds.setEnableAutoCommitOnReturn(true);
        ds.setRemoveAbandonedOnBorrow(true);
        ds.setRemoveAbandonedOnMaintenance(true);
        ds.setRemoveAbandonedTimeout(300);
        ds.setLogAbandoned(true);

        //ds.idle
        //ds.setMaxOpenPreparedStatements();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUsername(sql_user);
        ds.setPassword(sql_password);
        ds.setUrl(url);


        return ds;
    }

}