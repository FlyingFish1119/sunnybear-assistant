package com.fishsunny.assistant.mvc.controller;

import com.fishsunny.assistant.dto.RestResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * 网页内嵌探测控制器。
 * <p>
 * 前端想把外部网页用 iframe 从侧边栏滑出显示时，先来这里问一句「这个地址让不让嵌」。
 * 浏览器渲染 iframe 的成败取决于目标站点的两个响应头：
 * <ul>
 *     <li>{@code X-Frame-Options}（DENY / SAMEORIGIN / ALLOW-FROM）</li>
 *     <li>{@code Content-Security-Policy} 的 {@code frame-ancestors} 指令</li>
 * </ul>
 * 这两个头都不能从前端读取（跨域），但服务端发请求就能拿到，所以由后端探测后给出结论。
 * 探测失败或不允许时，前端退回「新标签页打开」，不会再傻等一片空白。
 * <p>
 * 安全说明：本接口会让服务端按客户端给的地址发起请求，属于典型的 SSRF 面。
 * 因此这里做了两层防护：只允许 http/https；解析出的目标 IP 落在回环 / 内网 / 链路本地 /
 * 组播等范围时一律拒绝，并且手动跟随重定向，每一跳都重新校验，避免用 302 绕到内网。
 *
 * @author FlyingFish-SunnyBear
 * @date 2026/9/30
 */
@RestController
@RequestMapping("/web")
public class WebViewController {

    private static final Logger log = LoggerFactory.getLogger(WebViewController.class);

    /** 探测请求超时（秒）：目标站慢就当作不可嵌，不值得让用户干等 */
    private static final int PROBE_TIMEOUT_SECONDS = 8;
    /** 手动跟随重定向的最大跳数 */
    private static final int MAX_REDIRECTS = 5;
    /** 部分站点对非浏览器 UA 返回 403，伪装成 Chrome 降低误判 */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private final HttpClient httpClient;

    public WebViewController(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * 判断目标地址能否被 iframe 内嵌。
     *
     * @param url       待探测的完整地址（前端需做 URL 编码）
     * @param appOrigin 发起嵌入的页面源（形如 {@code https://host:port}），
     *                  用于判断 SAMEORIGIN / frame-ancestors 'self' 与显式来源是否命中
     * @return {@code data} 为 {@link FrameCheck}：是否可嵌 + 不可嵌原因
     */
    @GetMapping("/frameable")
    public RestResponse frameable(@RequestParam("url") String url,
                                  @RequestParam(value = "origin", required = false) String appOrigin) {
        try {
            URI uri = parseHttpUri(url);
            if (uri == null) {
                return new RestResponse().success(new FrameCheck(false, "无效或不受支持的地址"));
            }
            String blocked = ssrfReason(uri);
            if (blocked != null) {
                return new RestResponse().success(new FrameCheck(false, blocked));
            }

            HttpResponse<InputStream> response = fetchHeaders(uri);
            try {
                int status = response.statusCode();
                if (status >= 400) {
                    return new RestResponse().success(
                            new FrameCheck(false, "目标返回 HTTP " + status + "，可能拒绝访问"));
                }
                boolean frameable = allowsFraming(response, uri, appOrigin);
                String reason = frameable ? null : "目标站点设置了嵌入限制（X-Frame-Options / CSP）";
                return new RestResponse().success(new FrameCheck(frameable, reason));
            } finally {
                closeQuietly(response);
            }
        } catch (SecurityException e) {
            // SSRF 校验在重定向过程中抛出，原因已经是可读文案
            return new RestResponse().success(new FrameCheck(false, e.getMessage()));
        } catch (Exception e) {
            log.warn("探测网页可嵌入性失败: {}", url, e);
            return new RestResponse().success(new FrameCheck(false, "探测失败：" + e.getMessage()));
        }
    }

    /* ==================== 探测：HEAD 优先，必要时退回 GET ==================== */

    /**
     * 只取响应头，不下载正文。优先用 HEAD；目标不支持（405/400/501）或直接拒绝时，
     * 退回 GET —— 有些服务器对 HEAD 的处理与实际 GET 不一致，GET 拿到的头才算数。
     * 重定向手动跟随，每一跳重新做 SSRF 校验。
     */
    private HttpResponse<InputStream> fetchHeaders(URI uri) throws Exception {
        URI current = uri;
        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            HttpResponse<InputStream> response = send(current, "HEAD");
            int status = response.statusCode();
            if (status == 400 || status == 403 || status == 405 || status == 501) {
                closeQuietly(response);
                response = send(current, "GET");
                status = response.statusCode();
            }
            if (!isRedirect(status)) {
                return response;
            }

            String location = response.headers().firstValue("Location").orElse(null);
            closeQuietly(response);
            if (location == null) {
                throw new IllegalStateException("重定向缺少 Location");
            }
            current = current.resolve(location.trim());
            String blocked = ssrfReason(current);
            if (blocked != null) {
                throw new SecurityException(blocked);
            }
        }
        throw new IllegalStateException("重定向次数过多");
    }

    private HttpResponse<InputStream> send(URI uri, String method) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(PROBE_TIMEOUT_SECONDS))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        // ofInputStream：拿到响应头即返回，正文流不读、直接关掉，避免大文件被拉进内存
        return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    private boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private void closeQuietly(HttpResponse<InputStream> response) {
        try {
            InputStream body = response.body();
            if (body != null) {
                body.close();
            }
        } catch (Exception ignored) {
            // 关闭失败无所谓，连接最终会被回收
        }
    }

    /* ==================== 响应头判定 ==================== */

    /**
     * 综合 X-Frame-Options 与所有 CSP 头的 frame-ancestors 指令判断是否允许嵌入。
     * 多个 CSP 头会同时生效，任何一条不允许都算不允许。
     */
    private boolean allowsFraming(HttpResponse<?> response, URI target, String appOrigin) {
        for (String value : response.headers().allValues("x-frame-options")) {
            for (String token : value.split(",")) {
                String t = token.trim().toLowerCase(Locale.ROOT);
                if (t.isEmpty()) {
                    continue;
                }
                if (t.startsWith("deny")) {
                    return false;
                }
                if (t.startsWith("sameorigin") && !sameOrigin(target, appOrigin)) {
                    return false;
                }
                // ALLOW-FROM 属于已被主流浏览器废弃的写法，不据此拒绝
            }
        }

        for (String csp : response.headers().allValues("content-security-policy")) {
            for (String directive : csp.split(";")) {
                String d = directive.trim().toLowerCase(Locale.ROOT);
                boolean isFrameAncestors = d.equals("frame-ancestors")
                        || d.startsWith("frame-ancestors ");
                if (isFrameAncestors && !frameAncestorsAllows(d, target, appOrigin)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 判断单条 frame-ancestors 指令是否放行 appOrigin */
    private boolean frameAncestorsAllows(String directive, URI target, String appOrigin) {
        String rest = directive.substring("frame-ancestors".length()).trim();
        if (rest.isEmpty()) {
            return true;
        }
        Origin app = parseOrigin(appOrigin);
        for (String token : rest.split("\\s+")) {
            String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            String lower = t.toLowerCase(Locale.ROOT);
            if (lower.equals("'none'")) {
                return false;
            }
            if (lower.equals("*")) {
                return true;
            }
            if (lower.equals("'self'")) {
                // 'self' 指资源自身来源，即目标站自己嵌自己
                if (sameOrigin(target, appOrigin)) {
                    return true;
                }
                continue;
            }
            if (lower.endsWith(":")) {
                // scheme-source，如 https:
                if (app != null && lower.equals(app.scheme + ":")) {
                    return true;
                }
                continue;
            }
            if (app != null && hostSourceMatches(t, app)) {
                return true;
            }
        }
        return false;
    }

    /**
     * host-source 匹配，支持 {@code *.example.com} 与 {@code example.com:443} / {@code *} 端口，
     * 可选 scheme 前缀。frame-ancestors 的来源不允许带路径，带了也只比较主机部分。
     */
    private boolean hostSourceMatches(String source, Origin app) {
        String s = source;
        String scheme = null;
        int schemeSep = s.indexOf("://");
        if (schemeSep >= 0) {
            scheme = s.substring(0, schemeSep).toLowerCase(Locale.ROOT);
            s = s.substring(schemeSep + 3);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        if (scheme != null && !scheme.equals(app.scheme)) {
            return false;
        }

        String hostPart = s;
        int port = -1;
        int colon = s.lastIndexOf(':');
        if (colon > 0) {
            String p = s.substring(colon + 1);
            hostPart = s.substring(0, colon);
            if (!p.equals("*")) {
                try {
                    port = Integer.parseInt(p);
                } catch (NumberFormatException e) {
                    return false;
                }
            }
        }

        boolean hostOk;
        if (hostPart.equals("*")) {
            hostOk = true;
        } else if (hostPart.startsWith("*.")) {
            String base = hostPart.substring(2).toLowerCase(Locale.ROOT);
            hostOk = app.host.equals(base) || app.host.endsWith("." + base);
        } else {
            hostOk = hostPart.equalsIgnoreCase(app.host);
        }
        if (!hostOk) {
            return false;
        }
        return port < 0 || port == app.port;
    }

    /* ==================== 地址与来源解析 ==================== */

    /** 解析成 http/https 的 URI，其他协议（file/data/javascript 等）一律拒绝 */
    private URI parseHttpUri(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.getHost() == null) {
                return null;
            }
            return uri;
        } catch (Exception e) {
            return null;
        }
    }

    private Origin parseOrigin(String origin) {
        if (origin == null || origin.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(origin.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            return new Origin(uri.getScheme().toLowerCase(Locale.ROOT),
                    uri.getHost().toLowerCase(Locale.ROOT),
                    effectivePort(uri.getScheme(), uri.getPort()));
        } catch (Exception e) {
            return null;
        }
    }

    private boolean sameOrigin(URI target, String appOrigin) {
        Origin app = parseOrigin(appOrigin);
        if (app == null) {
            return false;
        }
        return app.scheme.equalsIgnoreCase(target.getScheme())
                && app.host.equalsIgnoreCase(target.getHost())
                && app.port == effectivePort(target.getScheme(), target.getPort());
    }

    private int effectivePort(String scheme, int port) {
        if (port > 0) {
            return port;
        }
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    /**
     * SSRF 防护：解析目标主机，命中本机 / 内网 / 链路本地 / 组播地址就拒绝。
     *
     * @return 拒绝原因；通过校验返回 {@code null}
     */
    private String ssrfReason(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return "地址缺少主机名";
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress()) {
                    return "不允许探测内网或本机地址";
                }
            }
        } catch (Exception e) {
            return "无法解析主机名：" + host;
        }
        return null;
    }

    /* ==================== 内部类型 ==================== */

    /** 探测结果：前端据此决定「抽屉内嵌」还是「新标签页打开」 */
    public record FrameCheck(boolean frameable, String reason) {
    }

    /** 拆分后的来源信息 */
    private record Origin(String scheme, String host, int port) {
    }
}
