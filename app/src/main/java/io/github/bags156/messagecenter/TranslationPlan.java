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
    /** Letters outside links: how much a language guess has to go on. */
    static long letters(String text) {
        return text==null?0:text.replaceAll("(?i)https?://\\S+|www\\.\\S+", "").codePoints().filter(Character::isLetter).count();
    }
    /** Short outgoing fragments use the phone language, since language ID needs more context. */
    static String outgoingSource(String text,String detected,String phoneLanguage) {
        String from=language(detected);
        return from.isEmpty()||letters(text)<12?language(phoneLanguage):from;
    }
    /**
     * How sure language ID has to be before a draft counts as written in a language: ordinary
     * messages take its usual confident answer; a short one ("ya voy") needs a very confident
     * answer; a word or two ("ok", "sí") is too little to go on (above 1: never).
     */
    static float sureEnough(String text) {
        long n=letters(text);
        return n>=12?.6f:n>=4?.9f:2f;
    }
    /** Already written in the language it's sent in, so it goes as written, untranslated. */
    static boolean alreadyIn(String detected,String target) {
        String to=language(target);
        return !to.isEmpty()&&to.equals(language(detected));
    }
    /** A "translation" that only changed case, spacing, punctuation or emoji is what you typed: send that. */
    static boolean unchanged(String original,String translated) {
        String a=plain(original);
        return !a.isEmpty()&&a.equals(plain(translated));
    }
    private static String plain(String text) {
        StringBuilder out=new StringBuilder();
        if(text!=null)text.codePoints().filter(Character::isLetterOrDigit).map(Character::toLowerCase).forEach(out::appendCodePoint);
        return out.toString();
    }
    /** A declared writing language avoids misidentifying names, slang and mixed-language drafts. */
    static String outgoingSource(String text,String detected,String phoneLanguage,String configured) {
        return "auto".equals(configured)?outgoingSource(text,detected,phoneLanguage):language(configured==null||configured.isEmpty()?phoneLanguage:configured);
    }
    /** A translated result only belongs to this exact draft, reply and attachment set. */
    static boolean current(String chat,String text,String reply,String files,String expectedChat,String expectedText,String expectedReply,String expectedFiles) {
        return chat.equals(expectedChat)&&text.equals(expectedText)&&reply.equals(expectedReply)&&files.equals(expectedFiles);
    }
    private TranslationPlan() {}
}
