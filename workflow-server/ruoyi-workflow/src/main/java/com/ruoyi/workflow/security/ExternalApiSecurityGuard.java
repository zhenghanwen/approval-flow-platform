package com.ruoyi.workflow.security;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;

import org.springframework.stereotype.Component;

import com.ruoyi.common.exception.ServiceException;

/**
 * 外部接口调用安全守卫（PRD 17.2：SSRF 防护）。
 *
 * <p>在发起任何出站 HTTP 请求前校验目标地址：</p>
 * <ul>
 *     <li>拒绝私网 / 保留 / 环回 / 链路本地 / 云元数据地址（IPv4 + IPv6，含 v4-mapped 解包）；</li>
 *     <li>主机名先 DNS 解析，任一解析地址命中黑名单即拒绝（fail-closed）；</li>
 *     <li>主机白名单精确匹配保留（外部接口配置的 allowed_hosts_json）；</li>
 *     <li>{@link #readBounded}：响应有界读取，防超大响应拖垮内存。</li>
 * </ul>
 *
 * <p>配合调用方将 HttpClient 配置为 {@code followRedirects(NEVER)}，避免重定向绕过校验。</p>
 */
@Component
public class ExternalApiSecurityGuard {

    /** 有界响应读取上限：1MB（PRD 17.2 限制响应大小）。 */
    public static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    /** 云元数据地址（AWS/GCP/Azure 等）。 */
    private static final String CLOUD_METADATA_IP = "169.254.169.254";

    /**
     * 校验目标 URL：白名单精确匹配 + SSRF 黑名单校验。
     *
     * @param url          完整目标地址（scheme://host[:port]/path）
     * @param allowedHosts 允许访问的主机白名单（精确主机名/字面量 IP）
     * @return 校验通过后的 host
     * @throws ServiceException 白名单不匹配或目标命中 SSRF 黑名单
     */
    public String assertSafeTarget(String url, List<String> allowedHosts) {
        String host = extractHost(url);
        if (host == null || host.isBlank()) {
            throw new ServiceException("目标地址缺少主机名: " + url);
        }
        if (allowedHosts == null || allowedHosts.isEmpty()
            || allowedHosts.stream().noneMatch(h -> h != null && h.equalsIgnoreCase(host))) {
            throw new ServiceException("目标主机不在白名单内: " + host);
        }
        if (isBlockedHost(host)) {
            throw new ServiceException("目标主机命中 SSRF 黑名单（内网/保留/云元数据地址）: " + host);
        }
        return host;
    }

    /**
     * 判断主机名是否命中 SSRF 黑名单（fail-closed：解析失败也视为命中）。
     */
    public boolean isBlockedHost(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String h = stripBrackets(host.trim());
        InetAddress literal = parseLiteral(h);
        if (literal != null) {
            return isBlockedAddress(literal);
        }
        try {
            InetAddress[] all = InetAddress.getAllByName(h);
            for (InetAddress addr : all) {
                if (isBlockedAddress(addr)) {
                    return true;
                }
            }
            return all.length == 0;
        } catch (UnknownHostException | SecurityException e) {
            // 无法解析 → 无法证明安全，拒绝（fail-closed）
            return true;
        }
    }

    /**
     * 判断地址是否命中 SSRF 黑名单。
     */
    public boolean isBlockedAddress(InetAddress addr) {
        byte[] b = addr == null ? null : addr.getAddress();
        if (b == null) {
            return true;
        }
        if (b.length == 4) {
            return isBlockedIpv4(b);
        }
        if (b.length == 16) {
            return isBlockedIpv6(b);
        }
        return true;
    }

    /**
     * 有界读取输入流：超过 {@code maxBytes} 即抛异常，防止超大响应。
     */
    public byte[] readBounded(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new IOException("响应超过大小上限 " + maxBytes + " bytes，已中止读取");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** 默认 1MB 上限的有界读取。 */
    public byte[] readBounded(InputStream in) throws IOException {
        return readBounded(in, MAX_RESPONSE_BYTES);
    }

    private String extractHost(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private String stripBrackets(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    /**
     * 尝试把主机名当作字面量 IP 解析（不做 DNS，失败返回 null）。
     */
    private InetAddress parseLiteral(String host) {
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException | SecurityException e) {
            return null;
        }
    }

    private boolean isBlockedIpv4(byte[] b) {
        int ip = ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
        // byte 有符号：>127 的八位组必须 & 0xFF 转无符号再比较，否则 172==-84 恒 false
        int o0 = b[0] & 0xFF;
        int o1 = b[1] & 0xFF;
        int o2 = b[2] & 0xFF;
        if (o0 == 0) {
            return true;                      // 0.0.0.0/8 本网络
        }
        if (o0 == 10) {
            return true;                      // 10.0.0.0/8 私网
        }
        if ((ip & 0xFFC00000) == 0x64400000) {
            return true;                      // 100.64.0.0/10 CGNAT
        }
        if (o0 == 127) {
            return true;                      // 127.0.0.0/8 环回
        }
        if (o0 == 169 && o1 == 254) {
            return true;                      // 169.254.0.0/16 链路本地（含云元数据 169.254.169.254）
        }
        if (o0 == 172 && (o1 & 0xF0) == 16) {
            return true;                      // 172.16.0.0/12 私网
        }
        if (o0 == 192 && o1 == 0 && o2 == 0) {
            return true;                      // 192.0.0.0/24 IETF
        }
        if (o0 == 192 && o1 == 0 && o2 == 2) {
            return true;                      // 192.0.2.0/24 TEST-NET-1
        }
        if (o0 == 192 && o1 == 168) {
            return true;                      // 192.168.0.0/16 私网
        }
        if (o0 == 198 && (o1 & 0xFE) == 18) {
            return true;                      // 198.18.0.0/15 基准测试
        }
        if (o0 == 198 && o1 == 51 && o2 == 100) {
            return true;                      // 198.51.100.0/24 TEST-NET-2
        }
        if (o0 == 203 && o1 == 0 && o2 == 113) {
            return true;                      // 203.0.113.0/24 TEST-NET-3
        }
        if ((o0 & 0xF0) == 0xE0) {
            return true;                      // 224.0.0.0/4 组播
        }
        if ((o0 & 0xF0) == 0xF0) {
            return true;                      // 240.0.0.0/4 保留
        }
        return ip == 0xFFFFFFFF;              // 255.255.255.255
    }

    private boolean isBlockedIpv6(byte[] b) {
        if (isV4Mapped(b)) {
            byte[] v4 = { b[12], b[13], b[14], b[15] };
            return isBlockedIpv4(v4);         // ::ffff:a.b.c.d 解包后按 v4 判断
        }
        if (isAllZero(b)) {
            return true;                      // :: 未指定
        }
        if (isLoopbackV6(b)) {
            return true;                      // ::1
        }
        if (is6to4(b)) {
            byte[] v4 = { b[2], b[3], b[4], b[5] };
            return isBlockedIpv4(v4);         // 2002::/16 内嵌 v4（6to4 中转）
        }
        if ((b[0] & 0xFF) == 0xFE && (b[1] & 0xC0) == 0x80) {
            return true;                      // fe80::/10 链路本地
        }
        if ((b[0] & 0xFE) == 0xFC) {
            return true;                      // fc00::/7 ULA（站点本地）
        }
        if ((b[0] & 0xFF) == 0xFF) {
            return true;                      // ff00::/8 组播
        }
        if ((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x01
            && (b[2] & 0xFF) == 0x0D && (b[3] & 0xFF) == 0xB8) {
            return true;                      // 2001:db8::/32 文档
        }
        return false;
    }

    /** ::ffff:a.b.c.d */
    private boolean isV4Mapped(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return b[10] == 0 && b[11] == 0xFF;
    }

    private boolean isAllZero(byte[] b) {
        for (byte x : b) {
            if (x != 0) {
                return false;
            }
        }
        return true;
    }

    private boolean isLoopbackV6(byte[] b) {
        for (int i = 0; i < 15; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return b[15] == 1;
    }

    /** 2002::/16 6to4：v4 地址编码在字节 2–5。 */
    private boolean is6to4(byte[] b) {
        return b[0] == 0x20 && b[1] == 0x02;
    }

    /** 供测试/诊断引用的云元数据地址。 */
    public String cloudMetadataAddress() {
        return CLOUD_METADATA_IP;
    }
}
