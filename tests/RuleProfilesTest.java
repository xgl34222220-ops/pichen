package io.github.xgl34222220.bichen;

import java.util.HashMap;
import java.util.Map;

public final class RuleProfilesTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    public static void main(String[] args){
        for(String id:new String[]{"lite","balanced","enhanced"}){
            Map<String,Boolean> flags=RuleProfiles.flags(id);
            check(flags.size()==4,"all sources set explicitly");
            check(flags.get("china"),"pure ads enabled");
            check(!flags.get("tracking"),"privacy list never silently enabled");
            check(id.equals(RuleProfiles.identify(flags)),"derive profile from actual flags");
            Map<String,Boolean> custom=new HashMap<>(flags);custom.put("tracking",true);
            check("custom".equals(RuleProfiles.identify(custom)),"manual overrides show custom");
            try{flags.put("china",false);throw new AssertionError("immutable");}catch(UnsupportedOperationException expected){checks++;}
        }
        check(!RuleProfiles.flags("lite").get("adaway"),"lite reduces sources");
        check(RuleProfiles.flags("balanced").get("adaway"),"balanced includes AdAway");
        check(RuleProfiles.flags("enhanced").get("hagezi"),"enhanced includes HaGeZi");
        check(!RuleProfiles.flags("balanced").get("hagezi"),"balanced retains prior default");
        for(String bad:new String[]{"aggressive","","$(id)","enhanced;reboot"})try{RuleProfiles.flags(bad);throw new AssertionError("invalid profile");}catch(IllegalArgumentException expected){checks++;}
        System.out.println("PASS: rule profiles "+checks+" assertions");
    }
}
