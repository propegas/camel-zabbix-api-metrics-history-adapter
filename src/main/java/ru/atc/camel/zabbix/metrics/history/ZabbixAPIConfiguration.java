package ru.atc.camel.zabbix.metrics.history;

//import org.apache.camel.spi.UriParam;

import org.apache.camel.spi.UriParams;

@UriParams
public class ZabbixAPIConfiguration {

    private static final int DEFAULT_DELAY = 720;

    private String zabbixip;

    private String username;

    private String adaptername;

    private String password;

    private String source;

    private String zabbixapiurl;

    private String zabbixItemDescriptionPattern;

    private String zabbixItemKePattern;

    private String test;

    private String lastpolltime;

    private String zabbixMaxElementsLimit;

    private int batchRowCount;

    private int maxDiffTime;

    private int lastid;

    private int delay = DEFAULT_DELAY;

    private String dayInPast;

    public int getDelay() {
        return delay;
    }

    public void setDelay(int delay) {
        this.delay = delay;
    }

    public int getLastid() {
        return lastid;
    }

    public void setLastid(int lastid) {
        this.lastid = lastid;
    }

    public String getZabbixip() {
        return zabbixip;
    }

    public void setZabbixip(String zabbixip) {
        this.zabbixip = zabbixip;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getAdaptername() {
        return adaptername;
    }

    public void setAdaptername(String adaptername) {
        this.adaptername = adaptername;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getZabbixapiurl() {
        return zabbixapiurl;
    }

    public void setZabbixapiurl(String zabbixapiurl) {
        this.zabbixapiurl = zabbixapiurl;
    }

    public String getZabbixItemDescriptionPattern() {
        return zabbixItemDescriptionPattern;
    }

    public void setZabbixItemDescriptionPattern(String zabbixItemDescriptionPattern) {
        this.zabbixItemDescriptionPattern = zabbixItemDescriptionPattern;
    }

    public String getTest() {
        return test;
    }

    public void setTest(String test) {
        this.test = test;
    }

    public String getZabbixItemKePattern() {
        return zabbixItemKePattern;
    }

    public void setZabbixItemKePattern(String zabbixItemKePattern) {
        this.zabbixItemKePattern = zabbixItemKePattern;
    }

    public String getLastpolltime() {
        return lastpolltime;
    }

    public void setLastpolltime(String lastpolltime) {
        this.lastpolltime = lastpolltime;
    }

    public String getZabbixMaxElementsLimit() {
        return zabbixMaxElementsLimit;
    }

    public void setZabbixMaxElementsLimit(String zabbixMaxElementsLimit) {
        this.zabbixMaxElementsLimit = zabbixMaxElementsLimit;
    }

    public int getBatchRowCount() {
        return batchRowCount;
    }

    public void setBatchRowCount(int batchRowCount) {
        this.batchRowCount = batchRowCount;
    }

    public int getMaxDiffTime() {
        return maxDiffTime;
    }

    public void setMaxDiffTime(int maxDiffTime) {
        this.maxDiffTime = maxDiffTime;
    }

    public String getDayInPast() {
        return dayInPast;
    }

    public void setDayInPast(String dayInPast) {
        this.dayInPast = dayInPast;
    }
}