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

public final class Main {

    private static final int MAX_TOTAL = 20;
    private static final int IDLE_TIME_MILLIS = 300000;
    private static final int ABANDONED_TIMEOUT = 300;
    private static final int MAX_CONN_LIFETIME = 900000;

    private static String activemq_port;
    private static String activemq_ip;
    private static String sql_ip;
    private static String sql_port;
    private static String sql_database;
    private static String sql_user;
    private static String sql_password;
    private static String usejms;
    private static String source;
    private static String useSummarizeRoute = "false";
    private static String useMainRoute = "true";

    private static int maxConnLifetime = MAX_CONN_LIFETIME;

    private static Logger logger = LoggerFactory.getLogger(Main.class);

    private Main() {

    }

    public static void main(String[] args) throws Exception {

        logger.info("Starting Custom Apache Camel component example");
        logger.info("Press CTRL+C to terminate the JVM");

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

                ConnectionFactory connectionFactory = new ActiveMQConnectionFactory(
                        "tcp://" + activemq_ip + ":" + activemq_port
                );
                getContext().addComponent("activemq", JmsComponent.jmsComponentAutoAcknowledge(connectionFactory));

                SqlComponent sql = new SqlComponent();
                BasicDataSource ds = setupDataSource();
                sql.setDataSource(ds);
                getContext().addComponent("sql", sql);

                JdbcComponent jdbc = new JdbcComponent();
                jdbc.setDataSource(ds);
                getContext().addComponent("jdbc", jdbc);

                getContext().setAllowUseOriginalMessage(false);

                // Heartbeats
                if ("true".equals(usejms)) {
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
                    from(new StringBuilder()
                            .append("zabbixapi://metricshistory?")
                            .append("delay={{delay}}&").append("zabbixapiurl={{zabbixapiurl}}&")
                            .append("username={{username}}&")
                            .append("password={{password}}&")
                            .append("adaptername={{adaptername}}&")
                            .append("zabbixItemKePattern={{zabbixItemKePattern}}&")
                            .append("source={{source}}&")
                            .append("batchRowCount={{batchRowCount}}&")
                            .append("maxDiffTime={{maxDiffTime}}&")
                            .append("zabbixMaxElementsLimit={{zabbixMaxElementsLimit}}&")
                            .append("lastpolltime={{lastpolltime}}&")
                            .append("zabbixItemDescriptionPattern={{zabbixItemDescriptionPattern}}")
                            .toString())

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
                    from(new StringBuilder()
                            .append("zabbixapi://deletehistory?")
                            .append("delay={{delete_delay}}&")
                            .append("adaptername={{adaptername}}&")
                            .append("source={{source}}&")
                            .append("dayInPast={{dayInPast}}&")
                            .append("batchRowCount={{batchRowCount}}")
                            .toString()
                    )
                            .choice()
                            .when(header("queueName").isEqualTo("Metrics"))
                            .to("jdbc:BasicDataSource")
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
        ds.setMaxTotal(MAX_TOTAL);
        //ds.setMax
        ds.setMaxIdle(10);
        ds.setMinIdle(5);
        ds.setSoftMinEvictableIdleTimeMillis(IDLE_TIME_MILLIS);
        ds.setMaxConnLifetimeMillis(maxConnLifetime);
        ds.setLogExpiredConnections(true);
        //ds.setLogWriter(logger);
        ds.setDefaultAutoCommit(true);
        ds.setEnableAutoCommitOnReturn(true);
        ds.setRemoveAbandonedOnBorrow(true);
        ds.setRemoveAbandonedOnMaintenance(true);
        ds.setRemoveAbandonedTimeout(ABANDONED_TIMEOUT);
        ds.setLogAbandoned(true);

        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUsername(sql_user);
        ds.setPassword(sql_password);
        ds.setUrl(url);

        return ds;
    }

}