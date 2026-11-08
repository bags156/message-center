// SPDX-License-Identifier: AGPL-3.0-or-later

package heic

import (
	"os"
	"testing"
)

// Layers shaped like the Genmoji iPhones send (made with x265, not a real message):
// HEVC Range Extensions 4:4:4 colour and a monochrome alpha layer, 354x316.
func TestDecodesGenmojiLayers(t *testing.T) {
	for _, c := range []struct {
		file   string
		chroma int
	}{{"testdata/color444.hevc", 3}, {"testdata/alpha400.hevc", 0}} {
		stream, err := os.ReadFile(c.file)
		if err != nil {
			t.Fatal(err)
		}
		p, err := Decode(stream)
		if err != nil {
			t.Fatalf("%s: %v", c.file, err)
		}
		if p.Chroma != c.chroma || p.Widths[0] != 354 || p.Heights[0] != 316 {
			t.Fatalf("%s: got chroma %d, %dx%d", c.file, p.Chroma, p.Widths[0], p.Heights[0])
		}
		if c.chroma == 3 && (p.Widths[1] != 354 || len(p.Planes[2]) != 354*316) {
			t.Fatalf("%s: chroma planes wrong size", c.file)
		}
	}
}

func TestRejectsGarbage(t *testing.T) {
	if _, err := Decode(nil); err == nil {
		t.Fatal("empty stream accepted")
	}
	if _, err := Decode([]byte("<html>not a picture</html>")); err == nil {
		t.Fatal("garbage decoded")
	}
}
