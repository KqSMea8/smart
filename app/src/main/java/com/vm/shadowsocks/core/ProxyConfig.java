package com.vm.shadowsocks.core;

import android.annotation.SuppressLint;
import android.os.Build;

import com.vm.shadowsocks.tcpip.CommonMethods;
import com.vm.shadowsocks.tunnel.Config;
import com.vm.shadowsocks.tunnel.httpconnect.HttpConnectConfig;
import com.vm.shadowsocks.tunnel.shadowsocks.ShadowsocksConfig;

import org.apache.commons.net.util.SubnetUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;

import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class ProxyConfig {
    public static final ProxyConfig Instance = new ProxyConfig();
    public static boolean IS_DEBUG = false;
    public static String AppInstallID;
    public static String AppVersion;
    public final static int FAKE_NETWORK_MASK = CommonMethods.ipStringToInt("255.255.0.0");
    public final static int FAKE_NETWORK_IP = CommonMethods.ipStringToInt("172.25.0.0");

    ArrayList<IPAddress> m_IpList;
    ArrayList<IPAddress> m_DnsList;
    ArrayList<IPAddress> m_RouteList;
    public ArrayList<Config> m_ProxyList;

    HashMap<String, String> m_DomainMap; // 完全匹配
    HashMap<String, String> m_DomainKeywordMap; // 关键词匹配
    HashMap<String, String> m_DomainSuffixMap; // 前缀匹配
    HashMap<String, String> m_IPCountryMap; // ip country
    HashMap<String, String> m_IPCidrMap; // ip cidr

    public boolean globalMode = false;

    int m_dns_ttl;
    String m_welcome_info;
    String m_session_name;
    String m_user_agent;
    boolean m_isolate_http_host_header = true;
    int m_mtu;

    Timer m_Timer;

    public class IPAddress {
        public final String Address;
        public final int PrefixLength;

        public IPAddress(String address, int prefixLength) {
            this.Address = address;
            this.PrefixLength = prefixLength;
        }

        public IPAddress(String ipAddresString) {
            String[] arrStrings = ipAddresString.split("/");
            String address = arrStrings[0];
            int prefixLength = 32;
            if (arrStrings.length > 1) {
                prefixLength = Integer.parseInt(arrStrings[1]);
            }
            this.Address = address;
            this.PrefixLength = prefixLength;
        }

        @SuppressLint("DefaultLocale")
        @Override
        public String toString() {
            return String.format("%s/%d", Address, PrefixLength);
        }

        @Override
        public boolean equals(Object o) {
            if (o == null) {
                return false;
            } else {
                return this.toString().equals(o.toString());
            }
        }
    }

    public ProxyConfig() {
        m_IpList = new ArrayList<IPAddress>();
        m_DnsList = new ArrayList<IPAddress>();
        m_RouteList = new ArrayList<IPAddress>();
        m_ProxyList = new ArrayList<Config>();

        m_DomainMap = new HashMap<String, String>();
        m_DomainKeywordMap = new HashMap<String, String>();
        m_DomainSuffixMap = new HashMap<String, String>();

        m_IPCountryMap = new HashMap<String, String>();
        m_IPCidrMap = new HashMap<String, String>();

        m_Timer = new Timer();
        m_Timer.schedule(m_Task, 120000, 120000);//每两分钟刷新一次。
    }

    TimerTask m_Task = new TimerTask() {
        @Override
        public void run() {
            refreshProxyServer();//定时更新dns缓存
        }

        //定时更新dns缓存
        void refreshProxyServer() {
            try {
                for (int i = 0; i < m_ProxyList.size(); i++) {
                    try {
                        Config config = m_ProxyList.get(0);
                        InetAddress address = InetAddress.getByName(config.ServerAddress.getHostName());
                        if (address != null && !address.equals(config.ServerAddress.getAddress())) {
                            config.ServerAddress = new InetSocketAddress(address, config.ServerAddress.getPort());
                        }
                    } catch (Exception e) {
                    }
                }
            } catch (Exception e) {

            }
        }
    };


    public static boolean isFakeIP(int ip) {
        return (ip & ProxyConfig.FAKE_NETWORK_MASK) == ProxyConfig.FAKE_NETWORK_IP;
    }

    public Config getDefaultProxy() {
        if (m_ProxyList.size() > 0) {
            return m_ProxyList.get(0);
        } else {
            return null;
        }
    }

    public Config getDefaultTunnelConfig(InetSocketAddress destAddress) {
        return getDefaultProxy();
    }

    public IPAddress getDefaultLocalIP() {
        if (m_IpList.size() > 0) {
            return m_IpList.get(0);
        } else {
            return new IPAddress("172.25.0.1", 32);
        }
    }

    public ArrayList<IPAddress> getDnsList() {
        return m_DnsList;
    }

    public ArrayList<IPAddress> getRouteList() {
        return m_RouteList;
    }

    public int getDnsTTL() {
        if (m_dns_ttl < 30) {
            m_dns_ttl = 30;
        }
        return m_dns_ttl;
    }

    public String getWelcomeInfo() {
        return m_welcome_info;
    }

    public String getSessionName() {
        if (m_session_name == null) {
            m_session_name = getDefaultProxy().ServerAddress.getHostName();
        }
        return m_session_name;
    }

    public String getUserAgent() {
        if (m_user_agent == null || m_user_agent.isEmpty()) {
            m_user_agent = System.getProperty("http.agent");
        }
        return m_user_agent;
    }

    public int getMTU() {
        if (m_mtu > 1400 && m_mtu <= 20000) {
            return m_mtu;
        } else {
            return 1500;
        }
    }

    private String getDomainState(String domain) {
        domain = domain.toLowerCase();
        if (m_DomainMap.get(domain) != null) {
            if (ProxyConfig.IS_DEBUG)
                LocalVpnService.Instance.writeLog("getDomainState m_DomainMap " + domain + " -> " + m_DomainMap.get(domain));
            return m_DomainMap.get(domain);
        }

        for (String key : m_DomainSuffixMap.keySet()) {
            if (domain.endsWith(key)) {
                if (ProxyConfig.IS_DEBUG)
                    LocalVpnService.Instance.writeLog("getDomainState m_DomainSuffixMap " + domain + ":" + key + " -> " + m_DomainSuffixMap.get(key));
                return m_DomainSuffixMap.get(key);
            }
        }

        for (String key : m_DomainKeywordMap.keySet()) {
            if (domain.contains(key)) {
                if (ProxyConfig.IS_DEBUG)
                    LocalVpnService.Instance.writeLog("getDomainState m_DomainKeywordMap " + domain + ":" + key + " -> " + m_DomainKeywordMap.get(key));
                return m_DomainKeywordMap.get(key);
            }
        }

        return null;
    }

    public String needProxy(String host, int ip) {
        if (globalMode)
            return "proxy"; // TODO block

        if (host != null) {
            String action = getDomainState(host);

            if (action != null) {
                return action;
            }
        }

        if (ip != 0) {
            if (isFakeIP(ip)) {
                return "proxy";
            }

            String ipStr = CommonMethods.ipIntToString(ip);

            // ip country
            String countryIsoCode = LocalVpnService.Instance.getCountryIsoCodeByIP(ipStr);
            if (countryIsoCode != null) {
                countryIsoCode = countryIsoCode.toLowerCase();// 统一使用小写
                if (m_IPCountryMap.get(countryIsoCode) != null) {
                    if (ProxyConfig.IS_DEBUG)
                        LocalVpnService.Instance.writeLog("m_IPCountryMap " + ipStr + " " + countryIsoCode + " -> " + m_IPCountryMap.get(countryIsoCode));
                    return m_IPCountryMap.get(countryIsoCode);
                }
            }

            // ip cidr
            for (String key : m_IPCidrMap.keySet()) {
                SubnetUtils subnetUtils = new SubnetUtils(key);
                if (subnetUtils.getInfo().isInRange(ipStr)) {
                    if (ProxyConfig.IS_DEBUG)
                        LocalVpnService.Instance.writeLog("m_IPCidrMap " + ipStr + " is in " + key + " " + m_IPCidrMap.get(key));
                    return m_IPCidrMap.get(key);
                }
            }
        }

        return "direct";
    }

    public boolean isIsolateHttpHostHeader() {
        return m_isolate_http_host_header;
    }

    private String[] downloadConfig(String url) throws Exception {
        try {
            HttpClient client = new DefaultHttpClient();
            HttpGet requestGet = new HttpGet(url);

            requestGet.addHeader("X-Android-MODEL", Build.MODEL);
            requestGet.addHeader("X-Android-SDK_INT", Integer.toString(Build.VERSION.SDK_INT));
            requestGet.addHeader("X-Android-RELEASE", Build.VERSION.RELEASE);
            requestGet.addHeader("X-App-Version", AppVersion);
            requestGet.addHeader("X-App-Install-ID", AppInstallID);
            requestGet.setHeader("User-Agent", System.getProperty("http.agent"));
            HttpResponse response = client.execute(requestGet);

            String configString = EntityUtils.toString(response.getEntity(), "UTF-8");
            String[] lines = configString.split("\\n");
            return lines;
        } catch (Exception e) {
            throw new Exception(String.format("Download config file from %s failed.", url));
        }
    }

    private String[] readConfigFromFile(String path) throws Exception {
        StringBuilder sBuilder = new StringBuilder();
        FileInputStream inputStream = null;
        try {
            byte[] buffer = new byte[8192];
            int count = 0;
            inputStream = new FileInputStream(path);
            while ((count = inputStream.read(buffer)) > 0) {
                sBuilder.append(new String(buffer, 0, count, "UTF-8"));
            }
            return sBuilder.toString().split("\\n");
        } catch (Exception e) {
            throw new Exception(String.format("Can't read config file: %s", path));
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (Exception e2) {
                }
            }
        }
    }

    public void loadFromFile(InputStream inputStream) throws Exception {
        byte[] bytes = new byte[inputStream.available()];
        inputStream.read(bytes);
        loadFromLines(new String(bytes).split("\\r?\\n"));
    }

    public void loadFromUrl(String url) throws Exception {
        String[] lines = null;
        if (url.charAt(0) == '/') {
            lines = readConfigFromFile(url);
        } else {
            lines = downloadConfig(url);
        }
        loadFromLines(lines);
    }

    protected void loadFromLines(String[] lines) throws Exception {
        m_IpList.clear();
        m_DnsList.clear();
        m_RouteList.clear();
        m_ProxyList.clear();
        m_DomainMap.clear();
        m_DomainKeywordMap.clear();
        m_DomainSuffixMap.clear();

        int lineNumber = 0;
        for (String line : lines) {
            lineNumber++;

            if (line.trim().isEmpty() || line.trim().startsWith("#")) {
                continue;
            }

            String[] items = line.split("\\s+");
            if (items.length < 2) {
                continue;
            }

            String tagString = items[0].toLowerCase(Locale.ENGLISH).trim();
            try {
                if (!tagString.startsWith("#")) {
                    if (tagString.equals("ip")) {
                        addIPAddressToList(items, 1, m_IpList);
                    } else if (tagString.equals("dns")) {
                        addIPAddressToList(items, 1, m_DnsList);
                    } else if (tagString.equals("dns_ttl")) {
                        m_dns_ttl = Integer.parseInt(items[1]);
                    } else if (tagString.equals("mtu")) {
                        m_mtu = Integer.parseInt(items[1]);
                    } else if (tagString.equals("route")) {
                        addIPAddressToList(items, 1, m_RouteList);
                    } else if (tagString.equals("proxy")) {
                        addProxyToList(items, 1);
                    } else if (tagString.equals("welcome_info")) {
                        m_welcome_info = line.substring(line.indexOf(" ")).trim();
                    } else if (tagString.equals("session_name")) {
                        m_session_name = items[1];
                    } else if (tagString.equals("debug")) {
                        ProxyConfig.IS_DEBUG = convertToBool(items[1]);
                    } else if (tagString.equals("proxy_domain")) {
                        addDomainToHashMap(items, 1, "proxy");
                    } else if (tagString.equals("direct_domain")) {
                        addDomainToHashMap(items, 1, "direct");
                    } else if (tagString.equals("block_domain")) {
                        addDomainToHashMap(items, 1, "block");
                    } else if (tagString.equals("proxy_domain_keyword")) {
                        addDomainKeywordToHashMap(items, 1, "proxy");
                    } else if (tagString.equals("direct_domain_keyword")) {
                        addDomainKeywordToHashMap(items, 1, "direct");
                    } else if (tagString.equals("block_domain_keyword")) {
                        addDomainKeywordToHashMap(items, 1, "block");
                    } else if (tagString.equals("proxy_domain_suffix")) {
                        addDomainSuffixToHashMap(items, 1, "proxy");
                    } else if (tagString.equals("direct_domain_suffix")) {
                        addDomainSuffixToHashMap(items, 1, "direct");
                    } else if (tagString.equals("block_domain_suffix")) {
                        addDomainSuffixToHashMap(items, 1, "block");
                    } else if (tagString.equals("proxy_ip_country")) {
                        addIPCountryToHashMap(items, 1, "proxy");
                    } else if (tagString.equals("direct_ip_country")) {
                        addIPCountryToHashMap(items, 1, "direct");
                    } else if (tagString.equals("block_ip_country")) {
                        addIPCountryToHashMap(items, 1, "block");
                    } else if (tagString.equals("proxy_ip_cidr")) {
                        addIPCidrToHashMap(items, 1, "proxy");
                    } else if (tagString.equals("direct_ip_cidr")) {
                        addIPCidrToHashMap(items, 1, "direct");
                    } else if (tagString.equals("block_ip_cidr")) {
                        addIPCidrToHashMap(items, 1, "block");
                    } else if (tagString.equals("user_agent")) {
                        m_user_agent = line.substring(line.indexOf(" ")).trim();
                    } else if (tagString.equals("isolate_http_host_header")) {
                        m_isolate_http_host_header = convertToBool(items[1]);
                    }
                }
            } catch (Exception e) {
                throw new Exception(String.format("config file parse error: line:%d, tag:%s, error:%s", lineNumber, tagString, e));
            }

        }

        //查找默认代理。
        if (m_ProxyList.size() == 0) {
            tryAddProxy(lines);
        }
    }

    private void tryAddProxy(String[] lines) {
        for (String line : lines) {
            Pattern p = Pattern.compile("proxy\\s+([^:]+):(\\d+)", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(line);
            while (m.find()) {
                HttpConnectConfig config = new HttpConnectConfig();
                config.ServerAddress = new InetSocketAddress(m.group(1), Integer.parseInt(m.group(2)));
                if (!m_ProxyList.contains(config)) {
                    m_ProxyList.add(config);
                    m_DomainMap.put(config.ServerAddress.getHostName(), "direct");
                }
            }
        }
    }

    public void addProxyToList(String proxyString) throws Exception {
        Config config = null;
        if (proxyString.startsWith("ss://")) {
            config = ShadowsocksConfig.parse(proxyString);
        } else {
            if (!proxyString.toLowerCase().startsWith("http://")) {
                proxyString = "http://" + proxyString;
            }
            config = HttpConnectConfig.parse(proxyString);
        }
        if (!m_ProxyList.contains(config)) {
            m_ProxyList.add(config);
            m_DomainMap.put(config.ServerAddress.getHostName(), "direct");
        }
    }

    private void addProxyToList(String[] items, int offset) throws Exception {
        for (int i = offset; i < items.length; i++) {
            addProxyToList(items[i].trim());
        }
    }

    private void addDomainToHashMap(String[] items, int offset, String state) {
        for (int i = offset; i < items.length; i++) {
            String domainString = items[i].toLowerCase().trim();
            if (domainString.charAt(0) == '.') {
                domainString = domainString.substring(1);
            }
            m_DomainMap.put(domainString, state);
        }
    }

    private void addDomainKeywordToHashMap(String[] items, int offset, String state) {
        for (int i = offset; i < items.length; i++) {
            String domainString = items[i].toLowerCase().trim();
            if (domainString.charAt(0) == '.') {
                domainString = domainString.substring(1);
            }
            m_DomainKeywordMap.put(domainString, state);
        }
    }

    private void addDomainSuffixToHashMap(String[] items, int offset, String state) {
        for (int i = offset; i < items.length; i++) {
            String domainString = items[i].toLowerCase().trim();
            if (domainString.charAt(0) == '.') {
                domainString = domainString.substring(1);
            }
            m_DomainSuffixMap.put(domainString, state);
        }
    }

    private void addIPCountryToHashMap(String[] items, int offset, String state) {
        for (int i = offset; i < items.length; i++) {
            String domainString = items[i].toLowerCase().trim();
            if (domainString.charAt(0) == '.') {
                domainString = domainString.substring(1);
            }
            m_IPCountryMap.put(domainString, state);
        }
    }

    private void addIPCidrToHashMap(String[] items, int offset, String state) {
        for (int i = offset; i < items.length; i++) {
            String domainString = items[i].toLowerCase().trim();
            if (domainString.charAt(0) == '.') {
                domainString = domainString.substring(1);
            }
            m_IPCidrMap.put(domainString, state);
        }
    }

    private boolean convertToBool(String valueString) {
        if (valueString == null || valueString.isEmpty())
            return false;
        valueString = valueString.toLowerCase(Locale.ENGLISH).trim();
        if (valueString.equals("on") || valueString.equals("1") || valueString.equals("true") || valueString.equals("yes")) {
            return true;
        } else {
            return false;
        }
    }


    private void addIPAddressToList(String[] items, int offset, ArrayList<IPAddress> list) {
        for (int i = offset; i < items.length; i++) {
            String item = items[i].trim().toLowerCase();
            if (item.startsWith("#")) {
                break;
            } else {
                IPAddress ip = new IPAddress(item);
                if (!list.contains(ip)) {
                    list.add(ip);
                }
            }
        }
    }

}
