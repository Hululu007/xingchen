package com.github.catvod.net;

import androidx.annotation.NonNull;

import com.github.catvod.bean.Doh;
import com.github.catvod.utils.Util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.dnsoverhttps.DnsOverHttps;

public class OkDns implements Dns {

    private final ConcurrentHashMap<String, String> map;
    private volatile Supplier<Doh> supplier;
    private volatile DnsOverHttps doh;

    public OkDns() {
        this.map = new ConcurrentHashMap<>();
    }

    public synchronized void setDoh(Doh item) {
        HttpUrl url = HttpUrl.parse(item.getUrl());
        this.doh = url == null ? null : new DnsOverHttps.Builder().client(new OkHttpClient()).url(url).bootstrapDnsHosts(item.getHosts()).build();
        this.supplier = null;
        // 注意：不要 clear map，它是 hosts 覆盖表（接口下发的 host=ip），不是 DNS 缓存
    }

    public synchronized void setDoh(Supplier<Doh> supplier) {
        this.supplier = supplier;
    }

    /** 清除 DoH 设置，恢复系统 DNS（用于“系统”模式） */
    public synchronized void clearDoh() {
        this.doh = null;
        this.supplier = null;
        // 注意：不要 clear map，它是 hosts 覆盖表，不是 DNS 缓存
    }

    public void clear() {
        map.clear();
    }

    public void addAll(List<String> hosts) {
        map.putAll(hosts.stream().filter(Objects::nonNull).map(host -> host.split("=", 2)).filter(splits -> splits.length == 2).collect(Collectors.toMap(s -> s[0].trim(), s -> s[1].trim(), (oldHost, newHost) -> newHost)));
    }

    private String get(String hostname) {
        String target = map.get(hostname);
        if (target != null) return target;
        for (Map.Entry<String, String> entry : map.entrySet()) if (Util.containOrMatch(hostname, entry.getKey())) return entry.getValue();
        return hostname;
    }

    @NonNull
    @Override
    public List<InetAddress> lookup(@NonNull String hostname) throws UnknownHostException {
        Supplier<Doh> supplier = this.supplier;
        if (supplier != null) initDoh(supplier);
        String target = get(hostname);
        List<InetAddress> result;
        // 系统模式：先试系统 DNS，失败自动回退到腾讯 DoH（应对 DNS 污染）
        if (doh == null) {
            try {
                result = Dns.SYSTEM.lookup(target);
            } catch (UnknownHostException e) {
                // 系统 DNS 失败，回退到内置腾讯 DoH
                result = getFallbackDoh().lookup(target);
            }
        } else {
            result = doh.lookup(target);
        }
        // IPv4 优选过滤：有些网络的 DNS 会返回坏 IPv6（如 [::]）或劫持的回环地址（如 127.0.1.1），
        // 直接拿去连会失败。优先用 IPv4，无 IPv4 时才回退原列表（保证纯 IPv6 站点可用）。
        return preferIpv4(result);
    }

    /** IPv4 优选：过滤坏 IPv6 和劫持回环，优先返回 IPv4 */
    private List<InetAddress> preferIpv4(List<InetAddress> addresses) {
        if (addresses == null || addresses.size() <= 1) return addresses;
        List<InetAddress> ipv4 = new ArrayList<>();
        for (InetAddress addr : addresses) {
            // 跳过坏 IPv6（如 ::）和回环/本地劫持地址
            if (addr instanceof java.net.Inet6Address) continue;
            String host = addr.getHostAddress();
            if (host == null) continue;
            // 跳过 127.x.x.x 回环（劫持常见）
            if (host.startsWith("127.")) continue;
            ipv4.add(addr);
        }
        // 有可用 IPv4 就用 IPv4，否则回退原列表（纯 IPv6 站点）
        return ipv4.isEmpty() ? addresses : ipv4;
    }

    private volatile Dns fallbackDoh;

    private Dns getFallbackDoh() {
        if (fallbackDoh == null) {
            synchronized (this) {
                if (fallbackDoh == null) {
                    // 腾讯 DoH，bootstrap 用硬编码 IP 不依赖系统 DNS
                    try {
                        HttpUrl url = HttpUrl.parse("https://doh.pub/dns-query");
                        fallbackDoh = new DnsOverHttps.Builder()
                                .client(new OkHttpClient())
                                .url(url)
                                .bootstrapDnsHosts(
                                        java.net.InetAddress.getByName("119.29.29.29"),
                                        java.net.InetAddress.getByName("182.254.116.116"))
                                .build();
                    } catch (Exception e) {
                        // bootstrap 解析失败就不用回退
                        fallbackDoh = Dns.SYSTEM;
                    }
                }
            }
        }
        return fallbackDoh;
    }

    private synchronized void initDoh(Supplier<Doh> supplier) {
        if (supplier != this.supplier) return;
        setDoh(supplier.get());
    }
}
