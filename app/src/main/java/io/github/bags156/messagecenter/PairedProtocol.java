package io.github.bags156.messagecenter;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Google protocol presentation. IDs come from the protocol, never quote matching. */
final class PairedProtocol {
    static String string(JSONObject o,String key) { return o==null ? "":o.optString(key,""); }
    static long millis(long value) {
        if(value>100_000_000_000_000L) return value/1000;
        if(value>0 && value<100_000_000_000L) return value*1000;
        return value;
    }
    static long time(JSONObject m) { return millis(m.optLong("timestamp",m.optLong("lastMessageTimestamp",0))); }
    static String status(JSONObject m) { return string(m.optJSONObject("messageStatus"),"status"); }
    /**
     * Direction comes from the message status first: Google numbers outgoing
     * statuses 1-99 and incoming 100-199. The sender's isMe flag is not reliable
     * for incoming history, so it is only a fallback when the status says nothing.
     */
    static boolean outgoing(JSONObject m) { return outgoing(m,null); }
    static boolean outgoing(JSONObject m,JSONObject chat) {
        Boolean byStatus=outgoingStatus(status(m)); if(byStatus!=null) return byStatus;
        String id=m.optString("participantID","");
        if(!id.isEmpty() && chat!=null) {
            JSONArray ps=chat.optJSONArray("participants");
            if(ps!=null) for(int i=0;i<ps.length();i++) {
                JSONObject p=ps.optJSONObject(i);
                if(p!=null && id.equals(string(p.optJSONObject("ID"),"participantID"))) return p.optBoolean("isMe");
            }
        }
        JSONObject sender=m.optJSONObject("senderParticipant");
        return sender!=null && sender.optBoolean("isMe");
    }
    static Boolean outgoingStatus(String s) {
        if(s.startsWith("OUTGOING") || s.startsWith("MESSAGE_STATUS_OUTGOING")) return true;
        if(s.startsWith("INCOMING")) return false;
        try { int n=Integer.parseInt(s); if(n>=1 && n<100) return true; if(n>=100 && n<200) return false; }
        catch(NumberFormatException ignored) { }
        return null;
    }
    static boolean failed(JSONObject m) { String s=status(m); return s.contains("FAILED") || s.equals("OUTGOING_CANCELED"); }
    /** The other person in a one-to-one chat, or null for groups and unknown chats. */
    static JSONObject other(JSONObject chat) {
        if(chat==null || chat.optBoolean("isGroupChat")) return null;
        JSONArray ps=chat.optJSONArray("participants"); JSONObject found=null; int count=0;
        if(ps!=null) for(int i=0;i<ps.length();i++) {
            JSONObject p=ps.optJSONObject(i); if(p==null || p.optBoolean("isMe") || !p.optBoolean("isVisible",true)) continue;
            count++; if(found==null) found=p;
        }
        return count==1 ? found:null;
    }
    static String participantID(JSONObject p) { return p==null ? "":string(p.optJSONObject("ID"),"participantID"); }
    static String number(JSONObject p) { return p==null ? "":string(p.optJSONObject("ID"),"number"); }
    static boolean rcs(JSONObject chat) { return chat!=null && "RCS".equals(chat.optString("type")); }
    static boolean latestFromMe(JSONObject chat) {
        JSONObject latest=chat==null ? null:chat.optJSONObject("latestMessage");
        return latest!=null && latest.optLong("fromMe")!=0;
    }
    /** Google's "#RRGGBB" / "RRGGBB" / "AARRGGBB" avatar colors, or fallback. */
    static int color(String hex,int fallback) {
        if(hex==null) return fallback; String h=hex.trim(); if(h.startsWith("#")) h=h.substring(1);
        try {
            if(h.length()==6) return 0xff000000 | Integer.parseInt(h,16);
            if(h.length()==8) return (int)Long.parseLong(h,16);
        } catch(NumberFormatException ignored) { }
        return fallback;
    }
    static String initial(String title) {
        for(int i=0;i<title.length();) {
            int cp=title.codePointAt(i);
            if(Character.isLetterOrDigit(cp)) return new String(Character.toChars(Character.toUpperCase(cp)));
            i+=Character.charCount(cp);
        }
        return "#";
    }
    /** Text and a short label per attachment ("📷 Photo"), for previews, notifications, quotes and copying. */
    static String body(JSONObject m) {
        List<String> parts=new ArrayList<>();String text=text(m);if(!text.isEmpty())parts.add(text);
        JSONObject place=location(m);if(place!=null){String where=placeLabel(place.optString("text"));parts.add("📍 "+(where.isEmpty()?"Location":where));}
        for(JSONObject media:media(m))parts.add(mediaLabel(media));
        return String.join("\n",parts);
    }
    /** Only the typed text of a message, without attachments. */
    static String text(JSONObject m) {
        JSONArray infos=m.optJSONArray("messageInfo"); List<String> parts=new ArrayList<>();
        if(infos!=null) for(int i=0;i<infos.length();i++) {
            JSONObject part=infos.optJSONObject(i); if(part==null) continue;
            String text=string(part.optJSONObject("messageContent"),"content");if(!text.isEmpty())parts.add(text);
        }
        return String.join("\n",parts);
    }
    /**
     * A shared location: {"text": what Google sent (an address and/or a maps link),
     * "media": the map preview, when there is one}, or null.
     */
    static JSONObject location(JSONObject m) {
        JSONArray infos=m.optJSONArray("messageInfo");if(infos==null)return null;
        for(int i=0;i<infos.length();i++){JSONObject part=infos.optJSONObject(i);JSONObject place=part==null?null:part.optJSONObject("locationContent");if(place==null)continue;
            JSONObject out=new JSONObject();
            try{out.put("text",string(place.optJSONObject("messageContent"),"content"));JSONObject media=place.optJSONObject("mediaContent");
                if(media!=null){media.put("_message",m.optString("messageID"));media.put("_part",part.optString("actionMessageID"));media.put("_index",i);out.put("media",media);}}catch(Exception ignored){}
            return out;}
        return null;
    }
    /** A location's text without its link: usually the place name or address. */
    static String placeLabel(String text) { return text==null?"":text.replaceAll("https?://\\S+","").replaceAll("\\s+"," ").trim(); }
    /**
     * Group and chat events Google records as messages without text ("Sam left",
     * "Group renamed", "Now end-to-end encrypted"), or "" for anything else.
     */
    static String event(JSONObject m,JSONObject chat) {
        String s=status(m);if(!s.contains("TOMBSTONE"))return "";
        String who=person(chat,m.optString("participantID"),m.optJSONObject("senderParticipant"));if(who.isEmpty())who="Someone";
        String text=text(m);
        if(s.contains("SELF_REMOVED"))return "You were removed from the group";
        if(s.contains("PARTICIPANT_REMOVED"))return who+" was removed from the group";
        if(s.contains("SELF_LEFT"))return "You left the group";
        if(s.endsWith("PARTICIPANT_LEFT"))return who+" left";
        if(s.endsWith("PARTICIPANT_JOINED"))return who+" joined";
        if(s.contains("GROUP_RENAMED"))return text.isEmpty()?"Group renamed":"Group renamed to \u201c"+text+"\u201d";
        if(s.contains("GROUP_NAME_CLEARED"))return "Group name removed";
        if(s.endsWith("GROUP_CREATED"))return s.contains("ENCRYPTED")?"\uD83D\uDD12 Group created \u2022 end-to-end encrypted":"Group created";
        if(s.endsWith("ENCRYPTED_ONE_ON_ONE_RCS_CREATED")||s.endsWith("SWITCH_TO_ENCRYPTED_RCS"))return "\uD83D\uDD12 Chat is now end-to-end encrypted";
        if(s.endsWith("SWITCH_TO_TEXT"))return "Now texting with SMS/MMS";
        if(s.endsWith("SWITCH_TO_RCS"))return "Now chatting with RCS";
        if(s.endsWith("SWITCH_TO_GROUP_MMS"))return "Switched to group MMS";
        return "";
    }
    /** Attachments in the order Google lists them. */
    static List<JSONObject> media(JSONObject m) {
        List<JSONObject> out=new ArrayList<>();JSONArray infos=m.optJSONArray("messageInfo");
        // Each attachment remembers its message and part, which are needed to ask the phone for a full-size copy.
        if(infos!=null) for(int i=0;i<infos.length();i++) {JSONObject part=infos.optJSONObject(i);JSONObject media=part==null?null:part.optJSONObject("mediaContent");
            if(media!=null){try{media.put("_message",m.optString("messageID"));media.put("_part",part.optString("actionMessageID"));media.put("_index",i);}catch(Exception ignored){}out.add(media);}}
        return out;
    }
    /** "image", "video", "audio" or "file", from the MIME type (or the file name when Google leaves it out). */
    static String mediaKind(JSONObject media) {
        String mime=string(media,"mimeType").toLowerCase(java.util.Locale.ROOT),name=string(media,"mediaName").toLowerCase(java.util.Locale.ROOT);
        if(mime.startsWith("image/")||name.matches(".*\\.(jpe?g|png|gif|webp|heic|heif|bmp)$"))return "image";
        if(mime.startsWith("video/")||name.matches(".*\\.(mp4|3gp|mov|webm|mkv)$"))return "video";
        if(mime.startsWith("audio/")||name.matches(".*\\.(m4a|aac|amr|mp3|ogg|opus|wav)$"))return "audio";
        return "file";
    }
    static String mediaLabel(JSONObject media) {
        return switch(mediaKind(media)) {
            case "image" -> string(media,"mimeType").equalsIgnoreCase("image/gif")?"GIF":"📷 Photo";
            case "video" -> "🎥 Video";
            case "audio" -> "🎤 Audio";
            default -> { String name=string(media,"mediaName"); yield name.isEmpty()?"📎 File":"📎 "+name; }
        };
    }
    /** "RCS", "SMS", or "MMS", from Google's message type (falling back to the chat's type). */
    static String kind(JSONObject m,JSONObject chat) {
        return switch((int)m.optLong("type")) {
            case 1 -> "SMS"; case 2,3 -> "MMS"; case 4 -> "RCS";
            default -> chat!=null&&rcs(chat)?"RCS":!media(m).isEmpty()?"MMS":"SMS";
        };
    }
    /** Plain-language delivery state for message details. */
    static String statusLabel(JSONObject m) {
        String s=status(m);
        if(s.equals("OUTGOING_DISPLAYED"))return "Read";
        if(s.equals("OUTGOING_DELIVERED"))return "Delivered";
        if(s.equals("OUTGOING_COMPLETE"))return "Sent";
        if(s.startsWith("OUTGOING_FAILED")||s.contains("FAILED"))return "Not sent";
        if(s.startsWith("OUTGOING"))return "Sending";
        if(s.startsWith("INCOMING"))return "Received";
        return s.isEmpty()?"Unknown":s.toLowerCase(java.util.Locale.ROOT).replace('_',' ');
    }
    /** Human-readable size, e.g. "2.4 MB". */
    static String size(long bytes) {
        if(bytes<=0)return "";
        if(bytes<1024)return bytes+" B";
        if(bytes<1024*1024)return Math.round(bytes/1024.0)+" KB";
        return String.format(java.util.Locale.US,"%.1f MB",bytes/1048576.0);
    }
    static String name(JSONObject p) {
        if(p==null) return "";
        if(p.optBoolean("isMe")) return "You";
        String nick=Nicknames.get(number(p)); if(!nick.isEmpty()) return nick;
        for(String key:new String[]{"fullName","firstName","formattedNumber"}) {
            String value=string(p,key); if(!value.isEmpty()) return value;
        }
        return string(p.optJSONObject("ID"),"number");
    }
    static String person(JSONObject chat,String id,JSONObject supplied) {
        // Only trust the supplied sender for a name; its isMe flag can be wrong on incoming messages.
        String direct=supplied==null || supplied.optBoolean("isMe") ? "":name(supplied); if(!direct.isEmpty()) return direct;
        JSONArray participants=chat==null ? null:chat.optJSONArray("participants");
        if(participants!=null) for(int i=0;i<participants.length();i++) {
            JSONObject p=participants.optJSONObject(i);
            if(p!=null && id.equals(string(p.optJSONObject("ID"),"participantID"))) {
                String n=name(p); if(!n.isEmpty()) return n;
            }
        }
        return id.isEmpty() ? "Unknown participant":"Participant "+id;
    }
    static String title(JSONObject chat) {
        JSONObject o=other(chat); if(o!=null){String nick=Nicknames.get(number(o)); if(!nick.isEmpty()) return nick;}
        String n=string(chat,"name"); if(!n.isEmpty()) return n;
        JSONArray ps=chat.optJSONArray("participants"); List<String> names=new ArrayList<>();
        if(ps!=null) for(int i=0;i<ps.length();i++) {
            JSONObject p=ps.optJSONObject(i); if(p==null || p.optBoolean("isMe")) continue;
            String name=name(p); if(!name.isEmpty()) names.add(name);
        }
        return names.isEmpty() ? "Conversation "+string(chat,"conversationID"):String.join(", ",names);
    }
    /** Emoji for one reaction: Google's unicode when present, otherwise its reaction type. */
    static String emoji(JSONObject data) {
        String emoji=string(data,"unicode"); if(!emoji.isEmpty()) return emoji;
        return switch(string(data,"type")) {
            case "LOVE" -> "😍";case "RED_HEART" -> "❤️"; case "LIKE" -> "👍"; case "DISLIKE" -> "👎";
            case "LAUGH" -> "😂";case "SURPRISED" -> "😮";case "SAD" -> "😥";case "CRYING_FACE" -> "😢";
            case "ANGRY" -> "😠";case "POUTING_FACE" -> "😡";case "QUESTIONING" -> "🤔";default -> "";
        };
    }
    /** Every reaction on a message as {emoji, participantID}. One person can leave several emoji, and many people the same one. */
    static List<String[]> reactors(JSONObject m) {
        List<String[]> out=new ArrayList<>(); JSONArray entries=m.optJSONArray("reactions");
        if(entries!=null) for(int i=0;i<entries.length();i++) {
            JSONObject e=entries.optJSONObject(i); if(e==null) continue;
            String emoji=emoji(e.optJSONObject("data")); JSONArray actors=e.optJSONArray("participantIDs");
            if(emoji.isEmpty() || actors==null) continue;
            for(int j=0;j<actors.length();j++) { String id=actors.optString(j); if(!id.isEmpty()) out.add(new String[]{emoji,id}); }
        }
        return out;
    }
    static Map<String,List<String>> reactions(JSONObject m,JSONObject chat) {
        Map<String,List<String>> result=new LinkedHashMap<>();
        for(String[] r:reactors(m)) result.computeIfAbsent(r[0],k->new ArrayList<>()).add(person(chat,r[1],null));
        return result;
    }
    static JSONObject participant(JSONObject chat,String id) {
        JSONArray ps=chat==null ? null:chat.optJSONArray("participants");
        if(ps!=null && !id.isEmpty()) for(int i=0;i<ps.length();i++) {
            JSONObject p=ps.optJSONObject(i); if(p!=null && id.equals(participantID(p))) return p;
        }
        return null;
    }
    static boolean isMe(JSONObject chat,String id) { JSONObject p=participant(chat,id); return p!=null && p.optBoolean("isMe"); }
    /** Saved in your phone's contacts (Google sends a contact ID for those). */
    static boolean known(JSONObject p) { return p!=null && !p.optString("contactID").isEmpty(); }
    /** Every other person in the chat is a saved contact. */
    static boolean allKnown(JSONObject chat) { List<JSONObject> people=members(chat); if(people.isEmpty()) return false; for(JSONObject p:people) if(!known(p)) return false; return true; }
    /** People in the conversation other than you, as Google lists them. */
    static List<JSONObject> members(JSONObject chat) {
        List<JSONObject> out=new ArrayList<>(); JSONArray ps=chat==null ? null:chat.optJSONArray("participants");
        if(ps!=null) for(int i=0;i<ps.length();i++) {
            JSONObject p=ps.optJSONObject(i); if(p!=null && !p.optBoolean("isMe") && p.optBoolean("isVisible",true)) out.add(p);
        }
        return out;
    }
    /** Who is typing, by the phone numbers Google reports. Your own number is ignored. */
    static List<String> typingNames(JSONObject chat,List<String> numbers) {
        List<String> names=new ArrayList<>(); JSONArray ps=chat==null ? null:chat.optJSONArray("participants");
        for(String n:numbers) {
            JSONObject found=null;
            if(ps!=null) for(int i=0;i<ps.length();i++) { JSONObject p=ps.optJSONObject(i); if(p!=null && ChatText.sameNumber(n,number(p))) { found=p; break; } }
            if(found!=null && found.optBoolean("isMe")) continue;
            if(found==null) found=other(chat);
            String name=found==null ? "Someone":ChatText.firstName(name(found));
            if(!names.contains(name)) names.add(name);
        }
        return names;
    }
    static String cursor(JSONObject response) {
        JSONObject c=response.optJSONObject("cursor");
        return c==null || (string(c,"lastItemID").isEmpty() && c.optLong("lastItemTimestamp")==0) ? "":c.toString();
    }
    static boolean notifyLive(boolean old,boolean existed,JSONObject m) {
        return !old && !existed && !outgoing(m) && !body(m).isEmpty() && !status(m).equals("INCOMING_DISPLAYED") && !status(m).contains("TOMBSTONE");
    }
}
