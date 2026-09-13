package io.github.xgl34222220.bichen;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Optional, exact-domain CNAME filtering. No network lookups or Android dependencies. */
public final class DnsResponseFilter {
    private static final int MAX_ALIASES = 256, MAX_CHAIN = 32;
    private DnsResponseFilter() {}

    /** Returns the first blocked target on the queried name's answer chain, or null.
     * An explicit query-name allow bypasses the chain; allowing one target does not
     * whitelist its descendants. Unrelated answers, authority and glue do not decide.
     * Invalid or ambiguous reachable chains fail as protocol errors, never ad hits.
     */
    public static String blockedAlias(DnsPacket.Query query, byte[] response, boolean enabled,
            Predicate<String> allowed, Predicate<String> blocked) throws IOException {
        if (!enabled || query.queryClass != 1) return null;
        if (!DnsPacket.validResponse(query, response) || DnsPacket.truncated(response)
                || DnsPacket.ttlOffsets(response) == null) throw new IOException("DNS 别名响应格式无效");
        if ((response[3] & 15) != 0 || allowed.test(query.domain)) return null;
        int p = DnsPacket.nameEnd(response, 12) + 4;
        Map<String, String> aliases = new HashMap<>();
        int count = DnsPacket.u16(response, 6);
        for (int i = 0; i < count; i++) {
            int owner = p;
            p = DnsPacket.nameEnd(response, p);
            int type = DnsPacket.u16(response, p), clazz = DnsPacket.u16(response, p + 2);
            int data = p + 10, end = data + DnsPacket.u16(response, p + 8);
            if (type == 5 && clazz == 1) {
                String name = name(response, owner), target = name(response, data);
                String previous = aliases.put(name, target);
                if (previous != null && !previous.equals(target)) aliases.put(name, "");
                if (aliases.size() > MAX_ALIASES) throw new IOException("DNS 别名记录过多");
            }
            p = end;
        }
        String cursor = query.domain, match = null;
        Set<String> seen = new HashSet<>();
        // Validate the whole reachable chain before counting any match as a block.
        for (int hops = 0; ; hops++) {
            if (!seen.add(cursor)) throw new IOException("DNS 别名形成循环");
            String next = aliases.get(cursor);
            if (next == null) return match;
            if (next.isEmpty()) throw new IOException("DNS 别名目标冲突或为空");
            if (hops >= MAX_CHAIN) throw new IOException("DNS 别名链过长");
            if (match == null && !allowed.test(next) && blocked.test(next)) match = next;
            cursor = next;
        }
    }

    /** Decode using the same escaped-label representation as DnsPacket.Query. */
    private static String name(byte[] dns, int position) throws IOException {
        if (DnsPacket.nameEnd(dns, position) < 0) throw new IOException("DNS 域名压缩指针无效");
        StringBuilder out = new StringBuilder();
        int p = position;
        for (int step = 0; step < 128; step++) {
            int length = dns[p++] & 255;
            if (length == 0) return out.toString();
            if ((length & 0xc0) == 0xc0) {
                p = ((length & 63) << 8) | (dns[p] & 255);
                continue;
            }
            if (out.length() > 0) out.append('.');
            for (int j = 0; j < length; j++) {
                int c = dns[p++] & 255;
                if (c >= 'A' && c <= 'Z') c += 32;
                if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_') out.append((char)c);
                else out.append('\\').append(Character.forDigit(c >>> 4, 16)).append(Character.forDigit(c & 15, 16));
            }
        }
        throw new IOException("DNS 域名压缩层数过多");
    }
}
