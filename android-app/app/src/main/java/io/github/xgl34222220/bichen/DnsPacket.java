package io.github.xgl34222220.bichen;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.ArrayList;

/** IPv4/UDP DNS framing, deliberately independent of Android for protocol tests. */
public final class DnsPacket {
    public static final int MAX_DNS = 32739;
    private DnsPacket() {}

    public static final class Query {
        public final byte[] source, destination, dns;
        public final int sourcePort, id, type, queryClass;
        public final String domain;
        private final byte[] question;
        private Query(byte[] source, byte[] destination, int port, byte[] dns, Question q) {
            this.source = source; this.destination = destination; sourcePort = port;
            this.dns = dns; id = u16(dns, 0); type = q.type; queryClass = q.queryClass;
            domain = q.domain; question = q.wire;
        }
    }

    private static final class Question {
        String domain;
        byte[] wire;
        int type, queryClass;
    }

    /** Returns null for unrelated, malformed, fragmented or unsupported packets. */
    public static Query parse(byte[] packet, int size) {
        if (packet == null || size < 28 || size > packet.length) return null;
        if ((packet[0] & 0xf0) != 0x40) return null;
        int ihl = (packet[0] & 15) * 4, total = u16(packet, 2);
        if (ihl < 20 || total > size || total < ihl + 8 || (packet[9] & 255) != 17) return null;
        if ((u16(packet, 6) & 0x3fff) != 0 || u16(packet, ihl + 2) != 53) return null;
        int udpLength = u16(packet, ihl + 4);
        if (udpLength < 20 || udpLength > total - ihl || udpLength - 8 > MAX_DNS) return null;
        byte[] dns = Arrays.copyOfRange(packet, ihl + 8, ihl + udpLength);
        if ((u16(dns, 2) & 0xf800) != 0 || u16(dns, 4) != 1) return null;
        Question q = question(dns);
        if (q == null) return null;
        return new Query(Arrays.copyOfRange(packet, 12, 16), Arrays.copyOfRange(packet, 16, 20),
                u16(packet, ihl), dns, q);
    }

    private static Question question(byte[] dns) {
        if (dns.length < 17 || u16(dns, 4) != 1) return null;
        int p = 12, end = -1, steps = 0, wireLength = 1;
        boolean[] seen = new boolean[dns.length];
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        StringBuilder name = new StringBuilder();
        while (true) {
            if (p >= dns.length || seen[p] || ++steps > 128) return null;
            seen[p] = true;
            int len = dns[p++] & 255;
            if (len == 0) { if (end < 0) end = p; wire.write(0); break; }
            if ((len & 0xc0) == 0xc0) {
                if (p >= dns.length) return null;
                int target = ((len & 63) << 8) | (dns[p++] & 255);
                if (end < 0) end = p;
                if (target < 12 || target >= dns.length) return null;
                p = target; continue;
            }
            if ((len & 0xc0) != 0 || p + len > dns.length || (wireLength += len + 1) > 255) return null;
            wire.write(len);
            if (name.length() > 0) name.append('.');
            for (int i = 0; i < len; i++) {
                int c = dns[p++] & 255;
                wire.write(c);
                if (c >= 'A' && c <= 'Z') c += 32;
                if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_') {
                    name.append((char)c);
                } else {
                    name.append('\\').append(Character.forDigit(c >>> 4, 16)).append(Character.forDigit(c & 15, 16));
                }
            }
        }
        if (end + 4 > dns.length) return null;
        Question q = new Question();
        q.domain = name.toString(); q.type = u16(dns, end); q.queryClass = u16(dns, end + 2);
        wire.write(dns, end, 4); q.wire = wire.toByteArray();
        return q;
    }

    public static boolean validResponse(Query query, byte[] response) {
        if (response == null || response.length < 12 || response.length > MAX_DNS) return false;
        if (u16(response, 0) != query.id || (u16(response, 2) & 0xf800) != 0x8000) return false;
        if (u16(response, 4) == 0) return (response[3] & 15) != 0;
        Question q = question(response);
        return q != null && q.domain.equals(query.domain) && q.type == query.type && q.queryClass == query.queryClass;
    }

    public static boolean truncated(byte[] dns) { return dns.length >= 4 && (dns[2] & 2) != 0; }

    /** Validate wire lengths and every owner-name pointer; OPT's TTL field is flags, never a TTL. */
    static int[] ttlOffsets(byte[] dns) {
        if (dns == null || dns.length < 12 || dns.length > MAX_DNS || u16(dns, 4) != 1) return null;
        int p = nameEnd(dns, 12);
        if (p < 0 || p + 4 > dns.length) return null;
        p += 4;
        int records = u16(dns, 6) + u16(dns, 8) + u16(dns, 10), opts = 0;
        if (records > dns.length / 11) return null;
        ArrayList<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < records; i++) {
            int owner = p; p = nameEnd(dns, p);
            if (p < 0 || p + 10 > dns.length) return null;
            int type = u16(dns, p), length = u16(dns, p + 8), data = p + 10, end = data + length;
            if (end > dns.length) return null;
            if (type == 41) {
                if (++opts != 1 || dns[owner] != 0 || i < u16(dns, 6) + u16(dns, 8)
                        || (dns[p + 4] & 255) != 0 || (dns[p + 5] & 255) != 0) return null;
                // Parse option TLVs so a malformed ECS/cookie/padding cannot enter cache.
                int option = data;
                while (option < end) {
                    if (option + 4 > end) return null;
                    option += 4 + u16(dns, option + 2);
                    if (option > end) return null;
                }
            } else {
                if (type == 250 || type == 24) return null; // TSIG / SIG(0) cannot survive ID/TTL rewrites.
                offsets.add(p + 4);
                if (type == 1 && length != 4 || type == 28 && length != 16) return null;
                if (type == 2 || type == 5 || type == 12 || type == 39) {
                    if (nameEnd(dns, data) != end) return null;
                } else if (type == 6) {
                    int first = nameEnd(dns, data), second = first < 0 ? -1 : nameEnd(dns, first);
                    if (second < 0 || second + 20 != end) return null;
                } else if (type == 15 || type == 33) {
                    int skip = type == 15 ? 2 : 6;
                    if (length <= skip || nameEnd(dns, data + skip) != end) return null;
                }
            }
            p = end;
        }
        if (p != dns.length) return null;
        int[] result = new int[offsets.size()];
        for (int i = 0; i < result.length; i++) result[i] = offsets.get(i);
        return result;
    }

    static int nameEnd(byte[] dns, int position) {
        int p = position, end = -1, length = 1, steps = 0;
        while (p < dns.length && ++steps <= 128) {
            int at = p, n = dns[p++] & 255;
            if (n == 0) return end < 0 ? p : end;
            if ((n & 0xc0) == 0xc0) {
                if (p >= dns.length) return -1;
                int target = ((n & 63) << 8) | (dns[p++] & 255);
                if (target < 12 || target >= at) return -1;
                if (end < 0) end = p;
                p = target;
            } else {
                if ((n & 0xc0) != 0 || p + n > dns.length || (length += n + 1) > 255) return -1;
                p += n;
            }
        }
        return -1;
    }

    /** RFC 8484 requires accounting for an HTTP response's Age when reporting DNS TTLs. */
    static byte[] subtractAge(byte[] dns, long age) {
        if (age <= 0) return dns;
        int[] fields = ttlOffsets(dns);
        if (fields == null) return null;
        byte[] answer = dns.clone();
        for (int offset : fields) put32(answer, offset, Math.max(0L, u32(answer, offset) - age));
        return answer;
    }

    /** RFC 1035 NXDOMAIN (3), SERVFAIL (2) or another explicit DNS response code. */
    public static byte[] error(Query query, int rcode) {
        byte[] dns = new byte[12 + query.question.length];
        put16(dns, 0, query.id);
        put16(dns, 2, 0x8080 | (u16(query.dns, 2) & 0x0110) | (rcode & 15));
        put16(dns, 4, 1);
        System.arraycopy(query.question, 0, dns, 12, query.question.length);
        return dns;
    }

    public static byte[] responsePacket(Query query, byte[] dns) {
        if (dns == null || dns.length > MAX_DNS) throw new IllegalArgumentException("DNS response too large");
        byte[] out = new byte[28 + dns.length];
        out[0] = 0x45; put16(out, 2, out.length); put16(out, 4, query.id);
        out[8] = 64; out[9] = 17;
        System.arraycopy(query.destination, 0, out, 12, 4);
        System.arraycopy(query.source, 0, out, 16, 4);
        put16(out, 20, 53); put16(out, 22, query.sourcePort); put16(out, 24, dns.length + 8);
        System.arraycopy(dns, 0, out, 28, dns.length);
        put16(out, 10, checksum(out, 0, 20, 0));
        int sum = u16(out, 12) + u16(out, 14) + u16(out, 16) + u16(out, 18) + 17 + dns.length + 8;
        int udpChecksum = checksum(out, 20, out.length - 20, sum);
        put16(out, 26, udpChecksum == 0 ? 0xffff : udpChecksum);
        return out;
    }

    /** Reject TCP to the virtual DNS promptly; this service does not implement inbound TCP. */
    public static byte[] tcpReset(byte[] packet, int size) {
        if (packet == null || size < 40 || size > packet.length || (packet[0] & 0xf0) != 0x40) return null;
        int ihl = (packet[0] & 15) * 4, total = u16(packet, 2);
        if (ihl < 20 || total > size || total < ihl + 20 || (packet[9] & 255) != 6 || (u16(packet, 6) & 0x3fff) != 0) return null;
        if (u16(packet, ihl + 2) != 53 || (packet[ihl + 13] & 4) != 0) return null;
        int tcpHeader = ((packet[ihl + 12] >>> 4) & 15) * 4;
        if (tcpHeader < 20 || total < ihl + tcpHeader) return null;
        byte[] out = new byte[40]; out[0] = 0x45; put16(out, 2, 40); out[8] = 64; out[9] = 6;
        System.arraycopy(packet, 16, out, 12, 4); System.arraycopy(packet, 12, out, 16, 4);
        put16(out, 20, 53); put16(out, 22, u16(packet, ihl)); out[32] = 0x50;
        if ((packet[ihl + 13] & 16) != 0) {
            System.arraycopy(packet, ihl + 8, out, 24, 4); out[33] = 4;
        } else {
            long seq = u32(packet, ihl + 4) + total - ihl - tcpHeader;
            if ((packet[ihl + 13] & 2) != 0) seq++;
            if ((packet[ihl + 13] & 1) != 0) seq++;
            put32(out, 28, seq); out[33] = 20;
        }
        put16(out, 10, checksum(out, 0, 20, 0));
        int sum = u16(out, 12) + u16(out, 14) + u16(out, 16) + u16(out, 18) + 6 + 20;
        put16(out, 36, checksum(out, 20, 20, sum));
        return out;
    }

    static int checksum(byte[] bytes, int start, int length, int initial) {
        long sum = initial & 0xffffffffL;
        int end = start + length, p = start;
        while (p + 1 < end) { sum += u16(bytes, p); p += 2; }
        if (p < end) sum += (bytes[p] & 255) << 8;
        while ((sum >>> 16) != 0) sum = (sum & 65535) + (sum >>> 16);
        return (int)(~sum) & 65535;
    }
    static int u16(byte[] b, int p) { return ((b[p] & 255) << 8) | (b[p + 1] & 255); }
    private static long u32(byte[] b, int p) { return ((long)u16(b, p) << 16) | u16(b, p + 2); }
    private static void put16(byte[] b, int p, int n) { b[p] = (byte)(n >>> 8); b[p + 1] = (byte)n; }
    private static void put32(byte[] b, int p, long n) { put16(b, p, (int)(n >>> 16)); put16(b, p + 2, (int)n); }
}
