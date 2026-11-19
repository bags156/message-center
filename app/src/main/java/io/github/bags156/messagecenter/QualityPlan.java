package io.github.bags156.messagecenter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Prompt, splitting and literal-preservation decisions, without Android dependencies. */
final class QualityPlan {
    static final String SUFFIX="<end_of_turn>\n<start_of_turn>model\n";
    static String prefix(String from,String to){String source=Locale.forLanguageTag(from).getDisplayLanguage(Locale.ENGLISH),target=Locale.forLanguageTag(to).getDisplayLanguage(Locale.ENGLISH);
        return "<start_of_turn>user\nYou are a professional "+source+" ("+from+") to "+target+" ("+to+") translator. Your goal is to accurately convey the meaning and nuances of the original "+source+" text while adhering to "+target+" grammar, vocabulary, and cultural sensitivities.\n"
            +"Produce only the "+target+" translation, without any additional explanations or commentary. Please translate the following "+source+" text into "+target+":\n\n\n";}
    /** Preserve line breaks and split long paragraphs at sentence or word boundaries. */
    static List<String> fragments(String text){return fragments(text,800);}
    static List<String> fragments(String text,String source){return fragments(text,source.equals("zh")||source.equals("ja")||source.equals("ko")?300:800);}
    private static List<String> fragments(String text,int max){List<String> parts=new ArrayList<>();int start=0;
        while(start<text.length()){int line=text.indexOf('\n',start);int end=line<0?text.length():line+1;
            while(end-start>max){int cut=start+max;if(Character.isHighSurrogate(text.charAt(cut-1)))cut--;
                int sentence=-1,space=-1;for(int i=start+1;i<cut;i++){char c=text.charAt(i);if(Character.isWhitespace(c)){space=i+1;if(i>start&&".!?。！？".indexOf(text.charAt(i-1))>=0)sentence=i+1;}}
                if(sentence>start+max/4)cut=sentence;else if(space>start+max/4)cut=space;parts.add(text.substring(start,cut));start=cut;}
            parts.add(text.substring(start,end));start=end;}
        return parts;}
    private static final Pattern LITERALS=Pattern.compile("(?i)https?://[^\\s<>]+|[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|(?<![\\w])\\d{6,}(?![\\w])|(?<!\\d)\\d{1,2}:\\d{2}(?!\\d)");
    /** Fail rather than silently alter a link, email, verification code, time or emoji. */
    static boolean preserves(String original,String translated){Matcher m=LITERALS.matcher(original);
        while(m.find()){String value=m.group();if(value.regionMatches(true,0,"http",0,4))value=value.replaceAll("[.,!?;]+$","");if(!translated.contains(value))return false;}
        if(!emojiSequences(original).equals(emojiSequences(translated)))return false;
        return !translated.trim().isEmpty()&&!translated.contains("<start_of_turn>")&&!translated.contains("<end_of_turn>");}
    /** Treat joined emoji, flags, modifiers and keycaps as complete sequences. */
    private static Map<String,Integer> emojiSequences(String words){Map<String,Integer> found=new HashMap<>();
        for(int at=0;at<words.length();){int start=at,cp=words.codePointAt(at);at+=Character.charCount(cp);
            if(cp=='#'||cp=='*'||cp>='0'&&cp<='9'){int end=at;if(end<words.length()&&words.codePointAt(end)==0xfe0f)end++;
                if(end>=words.length()||words.codePointAt(end)!=0x20e3)continue;at=end+1;
            }else if(emoji(cp)){
                if(cp>=0x1f1e6&&cp<=0x1f1ff&&at<words.length()){int next=words.codePointAt(at);if(next>=0x1f1e6&&next<=0x1f1ff)at+=Character.charCount(next);}
                at=emojiModifiers(words,at);
                while(at<words.length()&&words.codePointAt(at)==0x200d){int nextAt=at+1;if(nextAt>=words.length()||!emoji(words.codePointAt(nextAt)))break;
                    at=emojiModifiers(words,nextAt+Character.charCount(words.codePointAt(nextAt)));}
            }else continue;
            String sequence=words.substring(start,at);found.put(sequence,found.getOrDefault(sequence,0)+1);
        }return found;}
    private static boolean emoji(int cp){return cp>=0x1f000&&cp<=0x1faff||cp>=0x2600&&cp<=0x27bf;}
    private static int emojiModifiers(String text,int at){while(at<text.length()){int cp=text.codePointAt(at);if(cp==0xfe0e||cp==0xfe0f||cp>=0x1f3fb&&cp<=0x1f3ff||cp>=0xe0020&&cp<=0xe007f)at+=Character.charCount(cp);else break;}return at;}
    private QualityPlan() {}
}
