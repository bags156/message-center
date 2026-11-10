package io.github.bags156.messagecenter;

import java.util.Locale;

/** Local preparation choices. MMS still has its smaller transport limit. */
final class PhotoQuality {
    static final String FAST="fast",STANDARD="standard",FULL="full";
    static String normalize(String value){return FAST.equals(value)?FAST:FULL.equals(value)?FULL:STANDARD;}
    static String label(String value){return switch(normalize(value)){case FAST->"Fast";case FULL->"Full quality";default->"Standard";};}
    static int edge(String value,boolean mms){if(mms)return 1600;return switch(normalize(value)){case FAST->1280;case FULL->Integer.MAX_VALUE;default->2560;};}
    static long bytes(String value,boolean mms){if(mms)return 950_000;return switch(normalize(value)){case FAST->650_000;case FULL->100_000_000;default->2_500_000;};}
    static int jpeg(String value){return switch(normalize(value)){case FAST->80;case FULL->95;default->88;};}
    static String size(long bytes){return bytes>=1_000_000?String.format(Locale.getDefault(),"%.1f MB",bytes/1_000_000.0):Math.max(1,Math.round(bytes/1000.0))+" KB";}
    private PhotoQuality(){}
}
