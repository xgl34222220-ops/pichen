package io.github.xgl34222220.bichen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

public final class DnsResponseFilterTest {
    static int checks;
    static void check(boolean result, String why) { checks++; if (!result) throw new AssertionError(why); }
    static byte[] name(String domain) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (String label : domain.split("\\.")) {
            b.write(label.length()); for (char c : label.toCharArray()) b.write(c);
        }
        b.write(0); return b.toByteArray();
    }
    static byte[] rr(String owner, String target, int type, int clazz) {
        byte[] n = name(owner), data = name(target), r = Arrays.copyOf(n, n.length + 10 + data.length);
        DnsCacheTest.put16(r,n.length,type); DnsCacheTest.put16(r,n.length+2,clazz);
        DnsCacheTest.put32(r,n.length+4,120); DnsCacheTest.put16(r,n.length+8,data.length);
        System.arraycopy(data,0,r,n.length+10,data.length);return r;
    }
    static byte[] response(DnsPacket.Query q, int an, int ns, int ar, byte[]...records) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] header = q.dns.clone();
        header[2]=(byte)0x81;header[3]=(byte)0x80;
        DnsCacheTest.put16(header,6,an);DnsCacheTest.put16(header,8,ns);DnsCacheTest.put16(header,10,ar);
        b.write(header,0,header.length);for(byte[] r:records)b.write(r,0,r.length);return b.toByteArray();
    }
    static String match(DnsPacket.Query q, byte[] a, String...allow) throws IOException {
        Set<String> white=new HashSet<>(Arrays.asList(allow));
        return DnsResponseFilter.blockedAlias(q,a,true,white::contains,s->s.equals("tracker.example"));
    }
    static void invalid(DnsPacket.Query q,byte[] a,String why)throws Exception {
        try {match(q,a);throw new AssertionError(why);}catch(IOException expected){checks++;}
    }
    public static void main(String[] args)throws Exception {
        DnsPacket.Query q=DnsCacheTest.query("metrics.shop.example",7);
        byte[] direct=response(q,1,0,0,rr(q.domain,"tracker.example",5,1));
        check("tracker.example".equals(match(q,direct)),"direct CNAME target blocked");
        check(match(q,direct,q.domain)==null,"query allow bypasses complete chain");
        check(match(q,direct,"tracker.example")==null,"explicit target allow wins");
        check(DnsResponseFilter.blockedAlias(q,direct,false,s->false,s->true)==null,"disabled preserves prior behavior");
        check(match(q,DnsCacheTest.answer(q,60))==null,"ordinary A response unaffected");
        byte[] chain=response(q,2,0,0,rr("cdn.example","tracker.example",5,1),rr(q.domain,"cdn.example",5,1));
        check("tracker.example".equals(match(q,chain)),"out-of-order multi-hop answers followed");
        check("tracker.example".equals(match(q,chain,"cdn.example")),"allow intermediate does not whitelist descendants");
        byte[] unrelated=response(q,2,0,0,rr(q.domain,"cdn.example",5,1),rr("elsewhere.example","tracker.example",5,1));
        check(match(q,unrelated)==null,"unrelated answer cannot cause false block");
        check(match(q,response(q,0,1,0,rr(q.domain,"tracker.example",5,1)))==null,"authority CNAME ignored");
        check(match(q,response(q,0,0,1,rr(q.domain,"tracker.example",5,1)))==null,"additional CNAME ignored");
        check(match(q,response(q,1,0,0,rr(q.domain,"tracker.example",5,3)))==null,"different class ignored");
        check(match(q,response(q,1,0,0,rr(q.domain,"tracker.example",12,1)))==null,"PTR is not a CNAME");
        check(match(q,response(q,1,0,0,rr(q.domain,"sub.tracker.example",5,1)))==null,"exact semantics not widened to subdomains");
        check("tracker.example".equals(match(q,response(q,1,0,0,rr(q.domain.toUpperCase(),"TRACKER.EXAMPLE",5,1)))),"ASCII case normalized");
        byte[] qclass=q.dns.clone();DnsCacheTest.put16(qclass,qclass.length-2,3);
        DnsPacket.Query chaos=DnsCacheTest.fromDns(qclass);
        check(match(chaos,response(chaos,1,0,0,rr(chaos.domain,"tracker.example",5,1)))==null,"non-IN query unaffected");
        byte[] aaaa=q.dns.clone();DnsCacheTest.put16(aaaa,aaaa.length-4,28);
        DnsPacket.Query ipv6=DnsCacheTest.fromDns(aaaa);
        check("tracker.example".equals(match(ipv6,response(ipv6,1,0,0,rr(q.domain,"tracker.example",5,1)))),"AAAA query follows same chain");
        byte[] queryCname=q.dns.clone();DnsCacheTest.put16(queryCname,queryCname.length-4,5);
        DnsPacket.Query cq=DnsCacheTest.fromDns(queryCname);
        check("tracker.example".equals(match(cq,response(cq,1,0,0,rr(q.domain,"tracker.example",5,1)))),"explicit CNAME question protected");
        check(match(q,response(q,2,0,0,rr(q.domain,"cdn.example",5,1),rr(q.domain,"cdn.example",5,1)))==null,"identical aliases harmless");
        invalid(q,response(q,2,0,0,rr(q.domain,"cdn.example",5,1),rr(q.domain,"tracker.example",5,1)),"ambiguous reachable owner rejected");
        check(match(q,response(q,2,0,0,rr("other.example","cdn.example",5,1),rr("other.example","tracker.example",5,1)))==null,"unrelated ambiguity cannot poison this query");
        invalid(q,response(q,2,0,0,rr(q.domain,"tracker.example",5,1),rr("tracker.example",q.domain,5,1)),"cycle rejected instead of ad hit");
        invalid(q,response(q,1,0,0,rr(q.domain,q.domain,5,1)),"self-loop bounded");
        byte[] wrong=direct.clone();wrong[0]^=1;invalid(q,wrong,"wrong ID rejected");
        byte[] tc=direct.clone();tc[2]|=2;invalid(q,tc,"truncated response rejected");
        byte[] shortAnswer=Arrays.copyOf(direct,direct.length-1);invalid(q,shortAnswer,"truncated RDATA rejected");
        // CNAME owner points back to question; target shares the question's 'example' label.
        byte[] prefix=name("tracker"), suffix={(byte)0xc0,25}; // metrics(8), shop(5), example begins at 25.
        byte[] compressed=new byte[12+8+2];compressed[0]=(byte)0xc0;compressed[1]=12;
        DnsCacheTest.put16(compressed,2,5);DnsCacheTest.put16(compressed,4,1);DnsCacheTest.put32(compressed,6,60);
        DnsCacheTest.put16(compressed,10,10);System.arraycopy(prefix,0,compressed,12,8);System.arraycopy(suffix,0,compressed,20,2);
        check("tracker.example".equals(match(q,response(q,1,0,0,compressed))),"compressed owner and target decoded");
        compressed[21]=(byte)(q.dns.length+12);invalid(q,response(q,1,0,0,compressed),"self-referential compression rejected");
        byte[][] many=new byte[33][];String owner=q.domain;
        for(int i=0;i<many.length;i++){String next="hop"+i+".example";many[i]=rr(owner,next,5,1);owner=next;}
        invalid(q,response(q,33,0,0,many),"chain length bounded");
        byte[] synthetic=DnsPacket.error(q,3);
        check((synthetic[3]&0x20)==0 && DnsPacket.u16(synthetic,6)==0,"synthetic block never claims authenticated data or keeps answers");
        // Existing cache entries must be inspected when the user enables this feature.
        DnsCache cache=new DnsCache();cache.put(q,direct,"v1");
        check(cache.get(q,"v1")!=null,"legitimate alias chain is cacheable before protection");
        check("tracker.example".equals(match(q,cache.get(q,"v1"))),"cached answers receive alias filtering too");
        check(match(q,cache.get(q,"v1"),q.domain)==null,"new whitelist overrides cached alias match");
        Random random=new Random(303);
        for(int i=0;i<25000;i++){
            byte[] fuzz=new byte[random.nextInt(800)];random.nextBytes(fuzz);
            try{match(q,fuzz);}catch(IOException expected){}
        }
        check(true,"25000 malformed responses never crash or hang");
        System.out.println("PASS: CNAME filter "+checks+" assertions, 25000 malformed responses");
    }
}
