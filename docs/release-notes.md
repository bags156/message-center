Message Center 1.0.2.

**Genmoji from iPhones now show**
- iPhones send Genmoji in a format that most Android phones can't decode in hardware (HEVC 4:4:4 colour plus a separate monochrome see-through layer). Google Messages shows these as "Can't load image". Message Center now decodes them in software and shows them see-through, like a big emoji.
- The software decoder (libde265) runs in a locked-down sandbox process with no permissions and no access to your messages or files, since anyone can send you a picture.

Install over 1.0.1; the package and signing key are unchanged.
