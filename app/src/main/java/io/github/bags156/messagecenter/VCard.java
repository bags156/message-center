package io.github.bags156.messagecenter;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The useful parts of a shared contact card (.vcf): name, phone numbers, emails, company. Plain Java for unit tests. */
final class VCard {
    String name = "", org = "";
    final List<String> phones = new ArrayList<>(), emails = new ArrayList<>();

    boolean empty() { return name.isEmpty() && phones.isEmpty() && emails.isEmpty(); }

    /** The first card in the text (vCard 2.1, 3.0 or 4.0). Never null; check empty(). */
    static VCard parse(String text) {
        VCard card = new VCard();
        if (text == null) return card;
        // Long lines are folded onto the next line starting with a space or tab; quoted-printable uses "=" at line ends.
        String unfolded = text.replace("\r\n", "\n").replace("\r", "\n").replaceAll("\n[ \t]", "").replaceAll("=\n", "");
        String structured = "";
        boolean inside = false;
        for (String line : unfolded.split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon), value = line.substring(colon + 1).trim();
            String[] params = key.split(";");
            String prop = params[0].toUpperCase(Locale.ROOT);
            int dot = prop.lastIndexOf('.'); if (dot >= 0) prop = prop.substring(dot + 1); // "item1.TEL"
            if (prop.equals("BEGIN")) { if (inside) break; inside = true; continue; }
            if (prop.equals("END")) { if (inside) break; continue; }
            String upperKey = key.toUpperCase(Locale.ROOT);
            if (upperKey.contains("ENCODING=QUOTED-PRINTABLE") || upperKey.contains(";QUOTED-PRINTABLE")) value = quotedPrintable(value, charset(params));
            switch (prop) {
                case "FN": if (card.name.isEmpty()) card.name = unescape(value).trim(); break;
                case "N": structured = value; break;
                case "TEL": { String n = unescape(value).replaceFirst("(?i)^tel:", "").trim(); if (!n.isEmpty() && !card.phones.contains(n)) card.phones.add(n); break; }
                case "EMAIL": { String e = unescape(value).trim(); if (!e.isEmpty() && !card.emails.contains(e)) card.emails.add(e); break; }
                case "ORG": if (card.org.isEmpty()) card.org = unescape(value.split("(?<!\\\\);")[0]).trim(); break;
                default: break;
            }
        }
        if (card.name.isEmpty() && !structured.isEmpty()) {
            String[] n = structured.split("(?<!\\\\);", -1);
            StringBuilder b = new StringBuilder();
            for (int i : new int[]{3, 1, 2, 0, 4}) if (i < n.length && !n[i].trim().isEmpty()) { if (b.length() > 0) b.append(' '); b.append(unescape(n[i]).trim()); }
            card.name = b.toString();
        }
        if (card.name.isEmpty() && !card.org.isEmpty()) card.name = card.org;
        return card;
    }

    private static Charset charset(String[] params) {
        for (String p : params) if (p.toUpperCase(Locale.ROOT).startsWith("CHARSET=")) { try { return Charset.forName(p.substring(8)); } catch (Exception ignored) {} }
        return StandardCharsets.UTF_8;
    }

    static String quotedPrintable(String value, Charset charset) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '=' && i + 2 < value.length()) {
                try { out.write(Integer.parseInt(value.substring(i + 1, i + 3), 16)); i += 2; continue; } catch (Exception ignored) {}
            }
            byte[] b = String.valueOf(ch).getBytes(charset); out.write(b, 0, b.length);
        }
        return new String(out.toByteArray(), charset);
    }

    private static String unescape(String v) {
        return v.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\:", ":").replace("\\\\", "\\");
    }
}
