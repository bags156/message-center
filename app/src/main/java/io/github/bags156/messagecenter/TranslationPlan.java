package io.github.bags156.messagecenter;

import java.util.Locale;

/** Pure decisions shared by manual, automatic and composer translation. */
final class TranslationPlan {
    static String language(String tag) {
        if (tag == null || tag.isEmpty() || "off".equals(tag) || "auto".equals(tag)) return "";
        String code=Locale.forLanguageTag(tag).getLanguage();
        return "iw".equals(code)?"he":"in".equals(code)?"id":code;
    }
    static String[] route(String from,String to) {
        from=language(from);to=language(to);
        if(from.isEmpty()||to.isEmpty()||from.equals(to))return new String[0];
        return "en".equals(from)||"en".equals(to)?new String[]{from+"-"+to}:new String[]{from+"-en","en-"+to};
    }
    /** Automatic detection ignores tiny/ambiguous fragments and link-only or numeric messages. */
    static boolean automatic(String text,boolean outgoing,boolean notice) {
        if(outgoing||notice||text==null||text.length()>6000)return false;
        String prose=text.replaceAll("(?i)https?://\\S+|www\\.\\S+", "").trim();
        return prose.codePoints().filter(Character::isLetter).count()>=12;
    }
    /** Symbols, numbers and links alone are already ready to send. */
    static boolean outgoingText(String text) {
        return text!=null&&text.replaceAll("(?i)https?://\\S+|www\\.\\S+", "").codePoints().anyMatch(Character::isLetter);
    }
    /** Short outgoing fragments use the phone language, since language ID needs more context. */
    static String outgoingSource(String text,String detected,String phoneLanguage) {
        String from=language(detected);
        return from.isEmpty()||text.replaceAll("(?i)https?://\\S+|www\\.\\S+", "").codePoints().filter(Character::isLetter).count()<12?language(phoneLanguage):from;
    }
    /** A translated result only belongs to this exact draft, reply and attachment set. */
    static boolean current(String chat,String text,String reply,String files,String expectedChat,String expectedText,String expectedReply,String expectedFiles) {
        return chat.equals(expectedChat)&&text.equals(expectedText)&&reply.equals(expectedReply)&&files.equals(expectedFiles);
    }
    private TranslationPlan() {}
}
